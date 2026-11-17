#!/usr/bin/env python3
"""Deterministic OSM XML -> offline Gaesin campus. No invented connecting edges.
Source extract + derived JSON are ODbL 1.0; see data/osm/README.md.
"""
import argparse
import json
import math
import xml.etree.ElementTree as ET
from collections import defaultdict, Counter
from pathlib import Path

R = 6371008.8
ORIGIN = (36.6285, 127.4565)
RELATION = '6705106'
WALKABLE = {'footway', 'pedestrian', 'path', 'steps', 'living_street', 'service', 'residential', 'unclassified'}

def project(lat, lon):
    return ((lon-ORIGIN[1])*math.pi/180*R*math.cos(math.radians(ORIGIN[0])), (lat-ORIGIN[0])*math.pi/180*R)

def cross(a,b): return a[0]*b[1]-a[1]*b[0]
def sub(a,b): return (a[0]-b[0],a[1]-b[1])
def inside(p, ring):
    result = False
    for a,b in zip(ring,ring[1:]+ring[:1]):
        ab=sub(b,a); ap=sub(p,a)
        if abs(cross(ab,ap)) < 1e-6 and min(a[0],b[0])-1e-7 <= p[0] <= max(a[0],b[0])+1e-7 and min(a[1],b[1])-1e-7 <= p[1] <= max(a[1],b[1])+1e-7: return True
        if (a[1]>p[1]) != (b[1]>p[1]) and p[0] < (b[0]-a[0])*(p[1]-a[1])/(b[1]-a[1])+a[0]: result=not result
    return result

def segment_inside(a,b,ring):
    if not inside(a,ring) or not inside(b,ring): return False
    v=sub(b,a); cuts=[0.,1.]
    for c,d in zip(ring,ring[1:]+ring[:1]):
        w=sub(d,c); den=cross(v,w)
        if abs(den)<1e-9: continue
        t=cross(sub(c,a),w)/den; u=cross(sub(c,a),v)/den
        if 0<t<1 and 0<=u<=1: cuts.append(t)
    cuts.sort()
    return all(inside((a[0]+v[0]*(lo+hi)/2,a[1]+v[1]*(lo+hi)/2),ring) for lo,hi in zip(cuts,cuts[1:]))

def tags(e): return {t.get('k'):t.get('v') for t in e.findall('tag')}
def point_json(p): return dict(x=round(p[0],3),y=round(p[1],3))

def convert(source):
    root=ET.parse(source).getroot()
    raw_nodes={e.get('id'):e for e in root.findall('node')}
    ways={e.get('id'):e for e in root.findall('way')}
    positions={i:project(float(e.get('lat')),float(e.get('lon'))) for i,e in raw_nodes.items()}
    relation=next(e for e in root.findall('relation') if e.get('id')==RELATION)
    chains=[[n.get('ref') for n in ways[m.get('ref')].findall('nd')] for m in relation.findall('member') if m.get('role')=='outer']
    boundary=chains.pop(0)
    while chains:
        for i,c in enumerate(chains):
            if boundary[-1] == c[0]: boundary.extend(c[1:]); break
            if boundary[-1] == c[-1]: boundary.extend(c[-2::-1]); break
        else: raise ValueError('Boundary incomplete; fetch relation/full')
        chains.pop(i)
    assert boundary[0]==boundary[-1]
    polygon=[positions[i] for i in boundary[:-1]]
    # Reuse only the explicitly ODbL OSM building outlines from SMASH-CBNU.
    # Game code, textures, terrain and VWorld additions are not bundled.
    smash=json.loads(Path('data/smash-cbnu/osm_building_outlines.json').read_text())
    phi=math.radians(smash['meta']['lat'])
    mlat=111132.92-559.82*math.cos(2*phi)+1.175*math.cos(4*phi)
    mlon=111412.84*math.cos(phi)-93.5*math.cos(3*phi)
    outlines={b['id']:[project(smash['meta']['lat']-z/mlat,smash['meta']['lon']+x/mlon) for x,z in b['outline']] for b in smash['buildings']}
    buildings=[]; edges=[]; used=set(); road_counts=Counter()
    for wid,w in ways.items():
        t=tags(w); refs=[n.get('ref') for n in w.findall('nd')]
        if not all(i in positions for i in refs): continue
        shape=[positions[i] for i in refs]
        if t.get('building') and len(shape)>3 and refs[0]==refs[-1] and all(inside(p,polygon) for p in shape):
            original=shape[:-1]
            candidate=outlines.get('way/'+wid,original)
            # Reject geometry mismatch or an outline spilling out of the campus boundary.
            shape=candidate if all(inside(p,polygon) for p in candidate) and all(min(math.dist(p,q) for q in original)<1 for p in candidate) else original
            xs,ys=zip(*shape); center=((min(xs)+max(xs))/2,(min(ys)+max(ys))/2)
            try: height=float(t['height'].replace(' m','')); height_source='osm:height'
            except (KeyError,ValueError):
                try: height=float(t['building:levels'])*3.2; height_source='estimated:levels*3.2m'
                except (KeyError,ValueError): height=10.; height_source='estimated:default10m'
            buildings.append(dict(id='way/'+wid,name=t.get('name',''),**point_json(center),width=round(max(xs)-min(xs),3),depth=round(max(ys)-min(ys),3),height=max(2.,min(height,120.)),heightSource=height_source,footprint=[point_json(p) for p in shape]))
        highway=t.get('highway')
        # Conditional restrictions require field review; don't silently assume them open.
        if highway not in WALKABLE or t.get('area')=='yes' or t.get('foot') in {'no','private'} or any('conditional' in k for k in t): continue
        if t.get('access') in {'no','private'} and t.get('foot') not in {'yes','designated','permissive'}: continue
        if t.get('construction') or t.get('indoor')=='yes' or t.get('tunnel')=='yes': continue
        for a,b in zip(refs,refs[1:]):
            if a==b or not segment_inside(positions[a],positions[b],polygon): continue
            # Shared OSM node IDs define topology. Never snap two nearby but unconnected ways.
            blocked=False
            for n in (a,b):
                nt=tags(raw_nodes[n])
                if nt.get('access') in {'no','private'} or nt.get('foot') in {'no','private'} or nt.get('barrier') in {'wall','fence'}: blocked=True
            if blocked: continue
            length=math.dist(positions[a],positions[b])
            if length<.01: continue
            if t.get('oneway:foot')=='-1': a,b=b,a
            edges.append(dict(from_=a,to=b,distance=round(length,6),directed=t.get('oneway:foot') in {'yes','1','-1'},osmWay=wid,highway=highway,verification='osm-only'))
            used.update((a,b)); road_counts[highway]+=1
    # Pick an existing mapped node near the mapped main-gate POI, not a fabricated gate coordinate.
    gate=positions['6394475285']
    adjacency=defaultdict(set)
    for e in edges: adjacency[e['from_']].add(e['to']); adjacency[e['to']].add(e['from_'])
    components=[]; remaining=set(used)
    while remaining:
        seen=set(); stack=[min(remaining)]
        while stack:
            n=stack.pop()
            if n in seen: continue
            seen.add(n); stack.extend(adjacency[n]-seen)
        components.append(seen); remaining-=seen
    largest=max(components,key=len)
    start=min(largest,key=lambda n:math.dist(positions[n],gate))
    destinations=[]
    for b in buildings:
        if not b['name']: continue
        footprint=[(p['x'],p['y']) for p in b['footprint']]
        def distance_to_outline(n):
            p=positions[n]; best=math.inf
            for a,c in zip(footprint,footprint[1:]+footprint[:1]):
                v=sub(c,a); vv=v[0]**2+v[1]**2
                t=max(0,min(1,((p[0]-a[0])*v[0]+(p[1]-a[1])*v[1])/vv)) if vv else 0
                best=min(best,math.dist(p,(a[0]+t*v[0],a[1]+t*v[1])))
            return best
        outside=[n for n in used if not inside(positions[n],footprint)]
        nearest=min(outside,key=distance_to_outline)
        gap=distance_to_outline(nearest)
        if gap<=60:
            destinations.append(dict(id=b['id'],name=b['name']+' 인근',nodeId=nearest,buildingId=b['id'],approachDistance=round(gap,1),entranceVerified=False))
    assert destinations and start
    nodes=[dict(id=n,**point_json(positions[n]),name='보행로 지점',latitude=float(raw_nodes[n].get('lat')),longitude=float(raw_nodes[n].get('lon'))) for n in sorted(used)]
    for n in nodes:
        if n['id']==start: n['name']='정문 인근 보행로'
    for e in edges: e['from']=e.pop('from_')
    data=dict(version='gaesin-osm-2026-11-17-v1',name='충북대학교 개신캠퍼스',source='OpenStreetMap',license='ODbL-1.0',attribution='© OpenStreetMap contributors',sourceUrl='https://www.openstreetmap.org/relation/6705106',downloadedAt='2026-11-17',origin=dict(latitude=ORIGIN[0],longitude=ORIGIN[1]),startNodeId=start,boundary=[point_json(p) for p in polygon],nodes=nodes,edges=edges,buildings=buildings,destinations=sorted(destinations,key=lambda d:d['name']))
    print(json.dumps(dict(nodes=len(nodes),edges=len(edges),buildings=len(buildings),destinations=len(destinations),components=sorted(map(len,components),reverse=True),roadTypes=dict(road_counts),start=start,gateOffset=math.dist(positions[start],gate)),ensure_ascii=False))
    return data

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--source',default='data/osm/gaesin.osm'); parser.add_argument('--output',default='app/src/main/assets/gaesin_campus.json'); args=parser.parse_args()
    Path(args.output).write_text(json.dumps(convert(args.source),ensure_ascii=False,indent=2)+'\n')
