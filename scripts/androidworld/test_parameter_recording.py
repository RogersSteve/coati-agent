"""Offline fixed-ten parameter and evidence contracts, no live device/model IO."""
import argparse
import copy
import dataclasses
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock,patch

p=argparse.ArgumentParser();p.add_argument('--aw-root',required=True);args=p.parse_args()
sys.path.insert(0,args.aw_root)
def forbidden(*a,**k):raise AssertionError('Device/network/external subprocess forbidden')
subprocess.Popen=forbidden;socket.create_connection=forbidden;socket.socket.connect=forbidden
from android_world import registry,suite_utils
from android_world.task_evals.utils import sqlite_schema_utils
from protocol import Results,ResumeCompatibility,ExecutionLedger,condition,identity,digest,parameter_evidence,atomic_new_json
from harness import reserve_before_start
from summarize import summarize,load_results


class RecordingContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.protocol={"experiment":{"tasks":json.loads(Path(__file__).with_name("smoke_tasks.json").read_text())["tasks"],"variants":1},"schema":3}
        cls.metadata={v['task_name']:v for v in json.loads((Path(args.aw_root)/'android_world/task_metadata.json').read_text())}
        cls.names=cls.protocol['experiment']['tasks']
        classes=registry.TaskRegistry().get_registry('android_world')
        cls.suite=suite_utils.create_suite({n:classes[n] for n in cls.names},1,seed=30)

    def test_fixed_ten_identity_reserve_roundtrip_source_params_unchanged(self):
        inspected={}
        with tempfile.TemporaryDirectory() as tmp:
            store=Results(tmp,condition('C2'))
            for name in self.names:
                task=self.suite[name][0];before=copy.deepcopy(task.params);original=task.params
                spec=identity(task,self.metadata,condition('C2'),1,0,self.protocol)
                store.reserve(spec);saved=json.loads(store.path(spec).with_suffix('.inprogress.json').read_text())
                self.assertEqual(saved,spec);self.assertIs(task.params,original);self.assertEqual(task.params,before)
                self.assertEqual(spec['official_max_steps'],suite_utils._allocate_step_budget(task.complexity))
                inspected[name]=spec['official_max_steps']
                if name in ('ExpenseAddMultipleFromGallery','RecipeAddSingleRecipe'):
                    rows=spec['params']['row_objects'];self.assertIn('__aw_row_type__',rows[0])
                    self.assertNotIn('category_id_to_name',rows[0]['fields'])
                    self.assertEqual(rows[0]['fields'],{f.name:getattr(task.params['row_objects'][0],f.name) for f in dataclasses.fields(task.params['row_objects'][0])})
        self.assertEqual(len(inspected),10)

    def test_json_parameters_unchanged_and_unknown_rejected(self):
        value={'s':'literal','a':[None,False,1,2.5]};self.assertEqual(parameter_evidence(value),value)
        for value in [object(),{'a':float('nan')},{1:'bad'},set()]:
            with self.assertRaises(TypeError):parameter_evidence(value)
        recipe=sqlite_schema_utils.Recipe(title='sample')
        self.assertEqual(parameter_evidence(recipe)['__aw_row_type__'],type(recipe).__module__+'.Recipe')

    def test_atomic_encode_failure_and_publication_failure_no_partial_or_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'identity.inprogress.json'
            with self.assertRaises(TypeError):atomic_new_json(path,{'a':object()})
            self.assertEqual(list(Path(tmp).iterdir()),[])
            with patch('protocol.os.link',side_effect=OSError('offline publication failure')):
                with self.assertRaises(OSError):atomic_new_json(path,{'a':1})
            self.assertEqual(list(Path(tmp).iterdir()),[])
            atomic_new_json(path,{'first':1});before=path.read_bytes()
            with self.assertRaises(FileExistsError):atomic_new_json(path,{'second':2})
            self.assertEqual(path.read_bytes(),before)

    def test_reserve_failure_no_ledger_attempt_init_or_dispatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=Results(tmp,condition('C2'));ledger=ExecutionLedger(Path(tmp)/'ledger.json',{'serial':'fixture'},'fixture')
            spec={'label':'ExpenseAddMultipleFromGallery_t1','task':'ExpenseAddMultipleFromGallery','run_id':'fixture','params':{'unsupported':object()}}
            initialize=Mock();dispatch=Mock()
            with self.assertRaises(TypeError):
                reserve_before_start(store,ledger,spec);initialize();dispatch()
            initialize.assert_not_called();dispatch.assert_not_called();self.assertEqual(ledger.state['attempts_since_restart'],0)
            self.assertIsNone(ledger.state['pending']);self.assertFalse(store.path(spec).with_suffix('.inprogress.json').exists())
            event=json.loads(next(store.directory.glob('stage-event-*.json')).read_text())
            self.assertEqual(event['kind'],'pre_initialize_adapter_failure');self.assertFalse(event['official_initialized']);self.assertEqual(event['RUN_TASK'],0)
            self.assertNotIn('success',event)

    def test_reserve_before_ledger_start_and_conflict_prevents_new_attempt(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=Results(tmp,condition('C2'));ledger=Mock()
            spec=identity(self.suite['ExpenseAddMultipleFromGallery'][0],self.metadata,condition('C2'),1,0,self.protocol)
            ledger.start.side_effect=lambda current:self.assertEqual(json.loads(store.path(spec).with_suffix('.inprogress.json').read_text()),current)
            reserve_before_start(store,ledger,spec);ledger.start.assert_called_once_with(spec)
            with self.assertRaises(FileExistsError):reserve_before_start(store,ledger,spec)
            ledger.start.assert_called_once()

    def test_summary_ignores_session_and_freeze_sidecars(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp)/"C2";folder.mkdir()
            for name in ("manual-freeze.json", "transport-session-fixture.json"):
                (folder/name).write_text("{}")
            (folder/"Question_t1.inprogress.json").write_text("{}")
            rows,paths,unfinished=load_results(tmp)
            self.assertEqual(rows,[]);self.assertEqual(paths,[]);self.assertEqual(len(unfinished),1)

if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(RecordingContracts))
    raise SystemExit(not result.wasSuccessful())
