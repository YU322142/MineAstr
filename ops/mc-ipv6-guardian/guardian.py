import asyncio
import contextlib
import http.client
import ipaddress
import json
import os
import pathlib
import re
import signal
import socket
import struct
import subprocess
import sys
import time

os.umask(0o007)
STATE = pathlib.Path('/state/status.json')
TABLE = 'mc_ipv6_guardian'


def log(event, **fields):
    print(json.dumps({'time': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                      'event': event, **fields}, ensure_ascii=False), flush=True)


def run(*args, input_text=None, timeout=12):
    result = subprocess.run(args, input=input_text, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed: {result.stderr.strip()[:400]}')
    return result.stdout


def parse_ra(text, gateway):
    sources = re.findall(r'^\s*from\s+(\S+)', text, re.M)
    if not sources or ipaddress.IPv6Address(sources[-1].split('%')[0]) != ipaddress.IPv6Address(gateway):
        raise RuntimeError('Router advertisement source does not match configured gateway')
    choices = []
    for block in re.split(r'\n\s*Prefix\s*:', text)[1:]:
        prefix = block.splitlines()[0].strip()
        pref = re.search(r'Pref\. time\s*:\s*(\d+)', block)
        valid = re.search(r'Valid time\s*:\s*(\d+)', block)
        net = ipaddress.IPv6Network(prefix, strict=True)
        if (net.prefixlen == 64 and net.network_address.is_global and pref and valid
                and int(pref.group(1)) > 0 and int(valid.group(1)) > 0):
            choices.append((net, int(pref.group(1)), int(valid.group(1))))
    if not choices:
        raise RuntimeError('Gateway advertised no usable, preferred public /64')
    return max(choices, key=lambda entry: entry[1])


def detect(config):
    text = run('rdisc6', '-1', '-r', '1', '-w', '3000', config['gateway'], config['interface'])
    return parse_ra(text, config['gateway'])


def varint(value):
    data = bytearray()
    while True:
        byte = value & 127
        value >>= 7
        data.append(byte | (128 if value else 0))
        if not value:
            return bytes(data)


def recv_varint(sock):
    result = 0
    for shift in range(0, 35, 7):
        data = sock.recv(1)
        if not data:
            raise RuntimeError('Minecraft backend closed status connection')
        result |= (data[0] & 127) << shift
        if not data[0] & 128:
            return result
    raise RuntimeError('Invalid Minecraft varint')


def backend_status(config):
    with socket.create_connection((config['backend_host'], config['backend_port']), timeout=5) as sock:
        host = config['domain'].encode()
        packet = varint(0) + varint(767) + varint(len(host)) + host + struct.pack('>H', config['backend_port']) + varint(1)
        sock.sendall(varint(len(packet)) + packet + b'\x01\x00')
        recv_varint(sock)
        if recv_varint(sock) != 0:
            raise RuntimeError('Unexpected Minecraft status packet')
        length = recv_varint(sock)
        if length > 1024 * 1024:
            raise RuntimeError('Oversized Minecraft status')
        data = bytearray()
        while len(data) < length:
            chunk = sock.recv(length - len(data))
            if not chunk:
                raise RuntimeError('Truncated Minecraft status')
            data.extend(chunk)
        status = json.loads(data)
        if config['expected_motd'] not in json.dumps(status.get('description'), ensure_ascii=False):
            raise RuntimeError('Backend MOTD does not match the intended MC server')
        return status


class Cloudflare:
    def __init__(self, config):
        self.config = config
        self.token = pathlib.Path('/run/secrets/cf_api_token').read_text().strip()

    def request(self, method, suffix, body=None):
        path = f"/client/v4/zones/{self.config['zone_id']}/dns_records/{self.config['record_id']}{suffix}"
        connection = http.client.HTTPSConnection('api.cloudflare.com', timeout=15)
        # DNS maintenance must work even while the previous IPv6 prefix is broken.
        original_create = connection._create_connection
        connection._create_connection = lambda address, timeout, source_address=None: original_create(
            (socket.gethostbyname(address[0]), address[1]), timeout, source_address)
        try:
            connection.request(method, path, body=None if body is None else json.dumps(body).encode(),
                headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
            response = connection.getresponse()
            if response.status >= 400:
                raise RuntimeError(f'Cloudflare HTTP {response.status}')
            result = json.loads(response.read())
        finally:
            connection.close()
        if not result.get('success'):
            codes = [item.get('code') for item in result.get('errors', [])]
            raise RuntimeError(f'Cloudflare API error codes: {codes}')
        return result['result']

    def update(self, address):
        record = self.request('GET', '')
        if record['name'] != self.config['domain'] or record['type'] != 'AAAA':
            raise RuntimeError('Cloudflare record identity mismatch')
        if record['content'] == address and record.get('proxied') is False and record['ttl'] == 60:
            return False
        updated = self.request('PATCH', '', {'content': address, 'ttl': 60, 'proxied': False,
                    'comment': 'Managed by mc-ipv6-guardian: current router prefix, dedicated Minecraft relay'})
        if updated['content'] != address or updated.get('proxied'):
            raise RuntimeError('Cloudflare did not apply the expected address')
        return True


def firewall(addresses, port):
    exists = subprocess.run(['nft', 'list', 'table', 'ip6', TABLE], capture_output=True).returncode == 0
    text = (f'delete table ip6 {TABLE}\n' if exists else '')
    text += f'table ip6 {TABLE} {{\n'
    for chain, hook in [('prerouting', 'prerouting'), ('output', 'output')]:
        text += f' chain {chain} {{ type nat hook {hook} priority dstnat; policy accept;\n'
        for address in addresses:
            text += f'  ip6 daddr {address} tcp dport 25565 dnat to [{address}]:{port}\n'
        text += ' }\n'
    text += ' chain input { type filter hook input priority 5; policy accept;\n'
    for address in addresses:
        text += f'  ip6 daddr {address} tcp dport {port} ct status dnat accept\n'
        text += f'  ip6 daddr {address} tcp dport {port} reject with tcp reset\n'
    text += ' }\n}\n'
    run('nft', '--check', '-f', '-', input_text=text)
    run('nft', '-f', '-', input_text=text)


class Guardian:
    def __init__(self, config):
        self.config = config
        self.cf = Cloudflare(config)
        self.listeners = {}
        self.addresses = {}
        self.previous = {}
        if STATE.exists():
            self.previous = json.loads(STATE.read_text())
            self.addresses = self.previous.get('managed_addresses', {})
        self.connections = 0
        self.stop = asyncio.Event()

    def save(self, **fields):
        self.previous.update(fields)
        self.previous['managed_addresses'] = self.addresses
        self.previous['active_connections'] = self.connections
        temp = STATE.with_suffix('.tmp')
        temp.write_text(json.dumps(self.previous, ensure_ascii=False, indent=2))
        temp.replace(STATE)

    async def relay(self, reader, writer):
        peer = writer.get_extra_info('peername')
        upstream = None
        self.connections += 1
        log('client_connected', source=peer[0])
        async def pipe(source, destination):
            while chunk := await source.read(65536):
                destination.write(chunk)
                await destination.drain()
            if destination.can_write_eof():
                destination.write_eof()
        try:
            backend_reader, upstream = await asyncio.wait_for(
                asyncio.open_connection(self.config['backend_host'], self.config['backend_port']), timeout=8)
            await asyncio.gather(pipe(reader, upstream), pipe(backend_reader, writer))
        except (ConnectionError, OSError, asyncio.TimeoutError):
            log('client_connection_ended', source=peer[0])
        finally:
            self.connections -= 1
            for stream in [upstream, writer]:
                if stream:
                    stream.close()
                    with contextlib.suppress(Exception):
                        await stream.wait_closed()

    async def listen(self, address):
        if address in self.listeners:
            return
        self.listeners[address] = await asyncio.start_server(self.relay, address,
                self.config['relay_port'], family=socket.AF_INET6)

    async def reconcile(self):
        network, preferred, valid = await asyncio.to_thread(detect, self.config)
        address = str(ipaddress.IPv6Address(int(network.network_address) + self.config['host_suffix']))
        await asyncio.to_thread(backend_status, self.config)
        await asyncio.to_thread(run, 'ip', '-6', 'addr', 'replace', address + '/128',
                'dev', self.config['interface'], 'preferred_lft', str(preferred), 'valid_lft', str(valid))
        self.addresses[address] = time.time()
        for attempt in range(5):
            try:
                await self.listen(address)
                break
            except OSError:
                if attempt == 4:
                    raise
                await asyncio.sleep(1)
        for old in list(self.addresses):
            if old != address and time.time() - self.addresses[old] > 600:
                if old in self.listeners:
                    self.listeners.pop(old).close()
                result = await asyncio.to_thread(subprocess.run,
                    ['ip', '-6', 'addr', 'del', old + '/128', 'dev', self.config['interface']], capture_output=True)
                del self.addresses[old]
            elif old != address:
                with contextlib.suppress(OSError):
                    await self.listen(old)
        await asyncio.to_thread(firewall, list(self.listeners), self.config['relay_port'])
        changed = await asyncio.to_thread(self.cf.update, address)
        if changed or self.previous.get('address') != address:
            log('ipv6_dns_ready', prefix=str(network), address=address, domain=self.config['domain'])
        self.save(address=address, prefix=str(network), last_success=time.time(), last_error=None,
                  domain=self.config['domain'], backend=f"{self.config['backend_host']}:{self.config['backend_port']}")

    async def main(self):
        loop = asyncio.get_running_loop()
        for sig in [signal.SIGTERM, signal.SIGINT]:
            loop.add_signal_handler(sig, self.stop.set)
        log('started', purpose='router-prefix tracking, dedicated MC IPv6 relay, Cloudflare AAAA maintenance')
        while not self.stop.is_set():
            try:
                await self.reconcile()
                wait = self.config.get('interval', 60)
            except Exception as error:
                log('repair_pending', error=str(error))
                self.save(last_error=str(error))
                wait = 15
            with contextlib.suppress(asyncio.TimeoutError):
                await asyncio.wait_for(self.stop.wait(), timeout=wait)
        for listener in self.listeners.values():
            listener.close()
            await listener.wait_closed()
        log('stopped', note='Managed address and DNS retained; use documented rollback to remove them')


def healthcheck():
    try:
        status = json.loads(STATE.read_text())
        return 0 if time.time() - status.get('last_success', 0) < 180 and not status.get('last_error') else 1
    except (OSError, ValueError):
        return 1


if __name__ == '__main__':
    if '--healthcheck' in sys.argv:
        sys.exit(healthcheck())
    configuration = json.loads(pathlib.Path('/app/config.json').read_text())
    asyncio.run(Guardian(configuration).main())
