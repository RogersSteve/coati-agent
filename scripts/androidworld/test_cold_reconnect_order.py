"""Offline native reconnect order; no device, model, or subprocess calls."""
import hashlib,unittest
from unittest.mock import Mock,MagicMock,patch
import runtime
from runtime import Bridge,restart_connection
from agent import EnvironmentFailure
from protocol import condition
C={'adb_path':'/offline/adb','adb_server_port':5039,'console_port':5556,'device_adb_port':5557,'grpc_port':8556,'serial':'emulator-5556','avd_name':'fixture'}
class ReconnectTests(unittest.TestCase):
 def test_native_connect_precedes_strict_binding_validation(self):
  owner=Mock();new=owner.new
  def connect(c):owner.connect(c);return new
  with patch.object(runtime,'connect',side_effect=connect):self.assertIs(restart_connection(owner.old,owner.bridge,C,condition('C1'),None,'id','apk'),new)
  calls=owner.mock_calls;self.assertLess(calls.index(unittest.mock.call.bridge.verify_identity('id','apk',require_bindings=False)),calls.index(unittest.mock.call.connect(C)));self.assertLess(calls.index(unittest.mock.call.connect(C)),calls.index(unittest.mock.call.bridge.verify_identity('id','apk')));self.assertIn(unittest.mock.call.bridge.check_bindings(),calls)
 def test_wrong_preconnect_identity_never_connects(self):
  bridge=Mock();bridge.verify_identity.side_effect=EnvironmentFailure('identity mismatch')
  with patch.object(runtime,'connect') as connect:
   with self.assertRaises(EnvironmentFailure):restart_connection(Mock(),bridge,C,condition('C1'),None,'id','apk')
   connect.assert_not_called();bridge.configure.assert_not_called()
 def test_postconnect_binding_failure_closes_new_environment(self):
  bridge=Mock();bridge.verify_identity.side_effect=[None,EnvironmentFailure('forwarder not bound')];new=Mock()
  with patch.object(runtime,'connect',return_value=new):
   with self.assertRaises(EnvironmentFailure):restart_connection(Mock(),bridge,C,condition('C1'),None,'id','apk')
  new.close.assert_called_once();bridge.configure.assert_not_called()
 def test_postconfigure_binding_failure_closes_new_environment(self):
  bridge=Mock();bridge.check_bindings.side_effect=EnvironmentFailure('binding lost');new=Mock()
  with patch.object(runtime,'connect',return_value=new):
   with self.assertRaises(EnvironmentFailure):restart_connection(Mock(),bridge,C,condition('C1'),None,'id','apk')
  new.close.assert_called_once();bridge.configure.assert_called_once()
 def test_actual_identity_default_strict_and_preconnect_identity_only(self):
  bench=Mock();bridge=Bridge(bench,C,'unused-offline');bridge.check_bindings=Mock();raw=b'offline-apk';expected=hashlib.sha256(raw).hexdigest()
  def output(*args,**kw):
   if args==('emu','avd','name'):return 'fixture\nOK'
   if args==('shell','settings','get','secure','android_id'):return 'id'
   if args==('shell','pm','path','bot.coati'):return 'package:/offline.apk'
   if args==('exec-out','cat','/offline.apk'):return raw
   raise AssertionError(args)
  bench.dev.sh.side_effect=output
  with patch.object(runtime.socket,'create_connection',return_value=MagicMock()):
   bridge.verify_identity('id',expected,require_bindings=False);bridge.check_bindings.assert_not_called()
   bridge.verify_identity('id',expected);bridge.check_bindings.assert_called_once()
if __name__=='__main__':unittest.main()
