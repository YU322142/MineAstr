import ipaddress,json,pathlib,subprocess
from guardian import Cloudflare,TABLE

config=json.loads(pathlib.Path('/app/config.json').read_text())
status_path=pathlib.Path('/state/status.json')
if status_path.exists():
 status=json.loads(status_path.read_text())
 for address in status.get('managed_addresses',{}):
  ip=ipaddress.IPv6Address(address)
  if ip.is_global and int(ip)&((1<<64)-1)==config['host_suffix']:
   subprocess.run(['ip','-6','addr','del',str(ip)+'/128','dev',config['interface']],check=False)
subprocess.run(['nft','delete','table','ip6',TABLE],check=False)
backup=json.loads(pathlib.Path('/state/dns-before.json').read_text())
if backup['name']!=config['domain'] or backup['id']!=config['record_id']:
 raise SystemExit('DNS backup identity mismatch')
payload={k:backup[k] for k in ['content','ttl','proxied']}
payload['comment']=backup.get('comment') or ''
Cloudflare(config).request('PATCH','',payload)
if status_path.exists():status_path.unlink()
print('Removed only guardian addresses/rules and restored original DNS record')
