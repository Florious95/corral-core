"""Declarative owned tmux fixture, independent of App terminal parser.
Use emit as the long-lived pane process; prepare supplies one JSON line through
its owned FIFO. ANSI goes to the real PTY only, never to App or a WebSocket.
"""
import argparse,json,os,sys
from pathlib import Path

def cell(text,bold=False):
 return [text,1,'Indexed(index=1)' if bold else 'Default','Default',bold,False,False,False,False,False]
def generate(ref,generation,cols,rows,history=20):
 if not generation.isalnum() or len(generation)>12 or cols<32 or rows<10 or history!=20:raise ValueError('bounded fixture geometry/generation')
 lines=[f'P15{generation}H{i:02d}' for i in range(history)]+[f'P15{generation}S{i:02d}' for i in range(rows)]
 if any(len(s)>=cols for s in lines):raise ValueError('fixture would wrap')
 ansi='\x1b[0m\x1b[2J\x1b[H'+ '\r\n'.join('\x1b[1;31m'+s+'\x1b[0m' for s in lines)+'\x1b[2;3H\x1b[?25h'
 grid=[[cell(c,True) for c in s]+[cell(' ') for _ in range(cols-len(s))] for s in lines[history:]]
 expected={'ref':ref,'Cols':cols,'Rows':rows,'CursorX':2,'CursorY':1,'CursorVisible':True,'AltScreen':False,'grid':grid,'history':[[cell(c,True) for c in s] for s in lines[:history]]}
 return ansi,expected

def main():
 p=argparse.ArgumentParser();p.add_argument('mode',choices=['emit','oracle']);p.add_argument('file',type=Path);a=p.parse_args()
 if a.mode=='oracle':
  spec=json.loads(a.file.read_text());print(json.dumps(generate(**spec)[1]));return
 # Caller creates/owns the FIFO before launching in its explicitly verified tmux socket.
 import stat
 if not stat.S_ISFIFO(a.file.stat().st_mode) or a.file.stat().st_uid!=os.getuid():raise SystemExit('not owned FIFO')
 while True:
  with a.file.open() as f:
   for line in f:
    spec=json.loads(line)
    if spec.get('stop'):return
    ansi,_=generate(**spec);sys.stdout.write(ansi);sys.stdout.flush()
if __name__=='__main__':main()
