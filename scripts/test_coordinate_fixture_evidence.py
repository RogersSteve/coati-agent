import copy
import unittest
from coordinate_fixture_evidence import validate_report

class EvidenceTest(unittest.TestCase):
    def report(self):
        return dict(version=1, run_id='run', instance_id='instance',model_calls=0,dispatches=3,phase='stale_armed',
            actions=[dict(type=x,hit=True,dispatch_returned=True) for x in ['tap','longpress','swipe']],
            artifacts=[dict(name=x+'.jpg') for x in ['tap','longpress','swipe','stale']],stale_binding={'raw':[1080,2400]})
    def test_initial(self):
        validate_report(self.report(),'run','initial')
    def test_final_pins_identity_and_original_evidence(self):
        before=self.report();after=copy.deepcopy(before)
        after.update(phase='passed',stale_dispatches=0,stale_rejection='stale_screenshot_coordinates',guarded_execution_entered=False)
        validate_report(after,'run','final',before)
        for key,value in [('instance_id','new'),('stale_dispatches',1),('guarded_execution_entered',True),('phase','failed'),('stale_binding',{})]:
            changed=copy.deepcopy(after);changed[key]=value
            with self.assertRaises(ValueError): validate_report(changed,'run','final',before)
    def test_failed_hits_counts_and_paths_rejected(self):
        for key,value in [('model_calls',1),('dispatches',4),('actions',[]),('artifacts',[{'name':'../secret'}])]:
            r=self.report();r[key]=value
            with self.assertRaises(ValueError):validate_report(r,'run','initial')

if __name__=='__main__':unittest.main()
