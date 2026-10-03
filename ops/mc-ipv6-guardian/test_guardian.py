import pathlib
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import guardian

RA = '''Soliciting router
 Prefix : 240e:3a2:5251:61d0::/64
 Valid time : 7200 seconds
 Pref. time : 3600 seconds
 from fe80::2070:b2ff:fe29:8d2c
'''

class Checks(unittest.TestCase):
    def test_live_prefix_and_source(self):
        net, pref, valid = guardian.parse_ra(RA, 'fe80::2070:b2ff:fe29:8d2c')
        self.assertEqual(str(net), '240e:3a2:5251:61d0::/64')
        self.assertEqual((pref, valid), (3600, 7200))
        with self.assertRaises(RuntimeError):
            guardian.parse_ra(RA, 'fe80::1')
        with self.assertRaises(RuntimeError):
            guardian.parse_ra(RA.replace('3600', '0'), 'fe80::2070:b2ff:fe29:8d2c')

    def test_preferred_prefix_wins_over_expired_prefix(self):
        text = RA.replace(' Prefix', ' Prefix : 240e:3a2:5252:850::/64\n Valid time : 300\n Pref. time : 0\n Prefix', 1)
        self.assertEqual(str(guardian.parse_ra(text, 'fe80::2070:b2ff:fe29:8d2c')[0]), '240e:3a2:5251:61d0::/64')

    def test_firewall_is_scoped_and_checked_before_commit(self):
        calls=[]
        with patch.object(guardian.subprocess, 'run') as result, patch.object(guardian, 'run', side_effect=lambda *a,**k:calls.append((a,k))):
            result.return_value.returncode=0
            guardian.firewall(['240e:3a2:5251:61d0::101'],25566)
        self.assertIn('--check',calls[0][0])
        text=calls[0][1]['input_text']
        self.assertNotIn('flush ruleset',text)
        self.assertIn('dnat to [240e:3a2:5251:61d0::101]:25566',text)
        self.assertIn('ct status dnat accept',text)

    def test_wrong_record_is_never_changed(self):
        cf=guardian.Cloudflare.__new__(guardian.Cloudflare)
        cf.config={'domain':'mc.322142.xyz'}
        cf.request=lambda *a: {'name':'other.322142.xyz','type':'AAAA'}
        with self.assertRaises(RuntimeError):cf.update('240e:3a2:5251:61d0::101')

    def test_unchanged_record_avoids_patch(self):
        cf=guardian.Cloudflare.__new__(guardian.Cloudflare)
        cf.config={'domain':'mc.322142.xyz'}
        calls=[]
        def request(method,*args):
            calls.append(method)
            return {'name':'mc.322142.xyz','type':'AAAA','content':'240e:3a2:5251:61d0::101','ttl':60,'proxied':False}
        cf.request=request
        self.assertFalse(cf.update('240e:3a2:5251:61d0::101'))
        self.assertEqual(calls,['GET'])

if __name__=='__main__':unittest.main()
