import json
import math
import unittest
from pathlib import Path
from import_osm import inside, segment_inside, project, ORIGIN

class GeometryTest(unittest.TestCase):
    def test_concave_boundary_does_not_allow_outside_shortcut(self):
        ring=[(0,0),(4,0),(4,1),(1,1),(1,4),(0,4)]
        self.assertTrue(inside((.5,3),ring))
        self.assertFalse(segment_inside((.5,3),(3,.5),ring))
        self.assertTrue(segment_inside((.5,3),(.5,.5),ring))
    def test_projection_origin(self):
        self.assertEqual(project(*ORIGIN),(0.,0.))
    def test_all_packaged_edges_stay_inside_boundary(self):
        d=json.loads(Path('app/src/main/assets/gaesin_campus.json').read_text())
        nodes={n['id']:n for n in d['nodes']}
        ring=[(p['x'],p['y']) for p in d['boundary']]
        for e in d['edges']:
            a=nodes[e['from']]; b=nodes[e['to']]
            self.assertTrue(segment_inside((a['x'],a['y']),(b['x'],b['y']),ring),str(e))
            self.assertAlmostEqual(e['distance'],math.hypot(a['x']-b['x'],a['y']-b['y']),delta=.003)
    def test_destinations_use_existing_nodes_without_fake_connectors(self):
        d=json.loads(Path('app/src/main/assets/gaesin_campus.json').read_text())
        ids={n['id'] for n in d['nodes']}
        self.assertIn(d['startNodeId'],ids)
        for p in d['destinations']:
            self.assertIn(p['nodeId'],ids)
            self.assertFalse(p['entranceVerified'])
            self.assertLessEqual(p['approachDistance'],60)
        self.assertTrue(all(e['osmWay'] and e['verification']=='osm-only' for e in d['edges']))

if __name__=='__main__': unittest.main()
