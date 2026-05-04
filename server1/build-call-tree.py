#!/usr/bin/env python3
"""
Convert async-profiler traces output to call tree format for OpenSearch
Builds a hierarchical tree structure from individual stack traces
"""

import sys
import json
import datetime
import re
from collections import defaultdict
from pathlib import Path

# Increase recursion limit for deep call trees
sys.setrecursionlimit(10000)

def extract_java_metadata(frame_name):
    """
    Extract Java metadata from frame name
    Returns (package, class_name, method) tuple
    """
    # Skip kernel frames
    if frame_name.endswith('_[k]'):
        return None, None, None

    # Skip thread names like [Thread-1 tid=123]
    if frame_name.startswith('[') and ']' in frame_name:
        return None, None, None

    # Skip [unknown] frames
    if frame_name == "[unknown]":
        return None, None, None

    # Parse Java method: package.Class.method or package.Class$Inner.method
    # async-profiler outputs use dot notation: io.netty.channel.epoll.Native.epollWait
    if '.' in frame_name and not frame_name.startswith('['):
        parts = frame_name.split('.')
        if len(parts) >= 2:
            method = parts[-1]
            class_parts = '.'.join(parts[:-1])

            # Extract package and class
            # Handle package.Class$Inner format
            last_dot = class_parts.rfind('.')
            if last_dot > 0:
                package = class_parts[:last_dot]
                clazz = class_parts[last_dot + 1:]
            else:
                package = ""
                clazz = class_parts

            return package, clazz, method

    return None, None, None

def parse_traces_file(input_file):
    """
    Parse async-profiler traces output format
    Returns list of traces with: duration_ns, sample_count, frames
    """
    traces = []
    current_trace = None

    with open(input_file, 'r') as f:
        for line in f:
            line = line.rstrip()
            if not line:
                continue

            # Match trace header: --- 1234567890 ns (12.34%), 123 samples (or 1 sample)
            header_match = re.match(r'^--- (\d+) ns \(([\d.]+)%\), (\d+) samples?$$', line)
            if header_match:
                if current_trace:
                    traces.append(current_trace)
                current_trace = {
                    'duration_ns': int(header_match.group(1)),
                    'percentage': float(header_match.group(2)),
                    'sample_count': int(header_match.group(3)),
                    'frames': []
                }
                continue

            # Match stack frame: [ 123] frame_name
            if current_trace is not None:
                frame_match = re.match(r'^\s*\[\s*(\d+)\]\s*(.+)$', line)
                if frame_match:
                    frame_depth = int(frame_match.group(1))
                    frame_name = frame_match.group(2).strip()
                    # Skip [unknown] frames
                    if frame_name != "[unknown]":
                        current_trace['frames'].append((frame_depth, frame_name))

        if current_trace:
            traces.append(current_trace)

    return traces

def build_call_tree(traces):
    """
    Build a hierarchical call tree from flat stack traces
    Traces are ordered: frame[0] is top of stack (leaf), frame[N] is bottom (root)
    Returns tree with aggregated sample counts at each node
    """
    # Create root node
    tree = {
        'name': 'root',
        'sample_count': 0,
        'self_samples': 0,
        'percentage': 0.0,
        'children': {},
        'depth': 0,
        'thread_id': None,
        'thread_name': None
    }

    # Collect Java packages and classes
    java_packages = set()
    java_classes = set()

    for trace in traces:
        sample_count = trace['sample_count']
        percentage = trace['percentage']
        frames = trace['frames']

        tree['sample_count'] += sample_count
        tree['percentage'] += percentage

        # Extract thread info from this trace (thread is at bottom of stack)
        thread_id, thread_name = extract_thread_info_from_frames(frames)
        if thread_id:
            tree['thread_id'] = thread_id
        if thread_name:
            tree['thread_name'] = thread_name

        # Navigate/create path through tree
        # frames[0] is top of stack (leaf), frames[-1] is root
        current_node = tree
        current_path = []
        for depth, frame_name in frames:
            current_path.append(frame_name)

            # Extract Java metadata from frame
            package, clazz, method = extract_java_metadata(frame_name)
            if package:
                java_packages.add(package)
            if clazz:
                java_classes.add(clazz)

            if frame_name not in current_node['children']:
                current_node['children'][frame_name] = {
                    'name': frame_name,
                    'sample_count': 0,
                    'self_samples': 0,
                    'percentage': 0.0,
                    'children': {},
                    'depth': current_node['depth'] + 1,
                    'path': list(current_path),
                    'thread_id': thread_id,
                    'thread_name': thread_name
                }

            current_node = current_node['children'][frame_name]
            # Increment total samples for this node
            current_node['sample_count'] += sample_count
            current_node['percentage'] += percentage

        # The first frame (top of stack) is where samples were collected
        if frames:
            leaf_frame_name = frames[0][1]
            if leaf_frame_name in tree['children']:
                tree['children'][leaf_frame_name]['self_samples'] += sample_count

    # Add Java metadata to tree
    tree['java_packages'] = sorted(list(java_packages))
    tree['java_classes'] = sorted(list(java_classes))

    return tree

def tree_to_list(node, path=None, max_depth=5):  # Reduced from 20 to 5
    """
    Convert tree dict to list format for JSON serialization
    Uses iterative approach to avoid recursion issues
    """
    if path is None:
        path = []

    # Build path without "root"
    current_name = node['name']
    if current_name != 'root':
        path = path + [current_name]

    result = {
        'name': node['name'],
        'sample_count': node['sample_count'],
        'self_samples': node['self_samples'],
        'percentage': round(node['percentage'], 2),
        'depth': node['depth'],
        'path': path,
        'children': []
    }

    # Include thread info if available
    if 'thread_id' in node:
        result['thread_id'] = node['thread_id']
    if 'thread_name' in node:
        result['thread_name'] = node['thread_name']

    # Limit tree depth to avoid excessive nesting
    if node['depth'] >= max_depth:
        result['name'] += ' [truncated]'
        return result

    # Sort children by sample count (descending)
    # Limit to top 5 children to keep size manageable for Fluent Bit
    sorted_children = sorted(
        node['children'].items(),
        key=lambda x: x[1]['sample_count'],
        reverse=True
    )[:5]  # Reduced from 10 to 5

    for child_name, child_node in sorted_children:
        # Only include children with significant sample counts
        if child_node['sample_count'] >= 10:  # Increased threshold from 5 to 10
            result['children'].append(
                tree_to_list(child_node, path, max_depth)
            )

    return result

def extract_thread_info_from_frames(frames):
    """
    Extract thread ID and name from frames list
    Thread format: [thread-name tid=12345]
    Returns (thread_id, thread_name) tuple
    """
    for _, frame_name in frames:
        # Match pattern: [thread-name tid=12345]
        match = re.search(r'\[(.+?)\s+tid=(\d+)\]', frame_name)
        if match:
            thread_name = match.group(1)
            thread_id = int(match.group(2))
            return thread_id, thread_name
    return None, None

def analyze_frame(frame_name):
    """
    Parse frame name to extract detailed info
    """
    frame_info = {
        'raw': frame_name,
        'name': frame_name,
        'type': 'java',
        'is_kernel': False
    }

    # Check for kernel frame
    if frame_name.endswith('_[k]'):
        frame_info['type'] = 'kernel'
        frame_info['is_kernel'] = True
        frame_info['name'] = frame_name.replace('_[k]', '')
    elif frame_name.startswith('[') and ']' in frame_name:
        # Thread name like [Thread-1 tid=123]
        frame_info['type'] = 'thread'
        frame_info['name'] = frame_name
    else:
        # Java frame - try to parse method/class/package
        if '/' in frame_name:
            # Format: package.Class.method or package.Class$Inner.method
            parts = frame_name.split('.')
            if len(parts) >= 2:
                method = parts[-1]
                class_parts = '.'.join(parts[:-1])

                # Extract package and class
                last_dot = class_parts.rfind('.')
                if last_dot > 0:
                    package = class_parts[:last_dot]
                    clazz = class_parts[last_dot + 1:]
                else:
                    package = ""
                    clazz = class_parts

                frame_info['method'] = method
                frame_info['class'] = clazz
                frame_info['package'] = package

    return frame_info

def convert_traces_to_call_tree(input_file, service_name="server1"):
    """
    Main conversion function: traces → call tree JSON
    Returns (list of profiles, traces_count)
    """
    timestamp = datetime.datetime.utcnow().isoformat() + "Z"

    # Parse traces
    traces = parse_traces_file(input_file)

    if not traces:
        return [], 0

    # Build tree (this also collects Java metadata)
    tree = build_call_tree(traces)

    # Convert to list
    call_tree_list = tree_to_list(tree)

    # Use metadata from tree
    total_samples = sum(t['sample_count'] for t in traces)
    total_traces = len(traces)
    java_packages = tree.get('java_packages', [])
    java_classes = tree.get('java_classes', [])

    # Create main profile with call tree
    profile = {
        'timestamp': timestamp,
        'service': service_name,
        'profiler': 'async-profiler',
        'profiler_version': '4.1',
        'profile_type': 'call_tree',
        'total_samples': total_samples,
        'total_traces': total_traces,
        'unique_packages': len(java_packages),
        'unique_classes': len(java_classes),
        'java_packages': java_packages,
        'java_classes': java_classes,
        'call_tree': call_tree_list
    }

    # Also create flat summary profiles for top hot paths
    # This makes it easier to query in OpenSearch
    profiles = [profile]

    # Add top hot paths as individual profiles
    # Include all paths with thread information (no filtering)
    sorted_children = sorted(
        tree['children'].items(),
        key=lambda x: x[1]['sample_count'],
        reverse=True
    )[:50]

    for child_name, child_node in sorted_children:
        # Get thread info and path from the child node
        path = child_node.get('path', [])
        thread_id = child_node.get('thread_id')
        thread_name = child_node.get('thread_name')

        hot_path_profile = {
            'timestamp': timestamp,
            'service': service_name,
            'profiler': 'async-profiler',
            'profiler_version': '4.1',
            'profile_type': 'hot_path',
            'path_name': child_name,
            'sample_count': child_node['sample_count'],
            'self_samples': child_node['self_samples'],
            # 'percentage': 제거됨 (sample_count / total_samples * 100으로 계산 가능)
            'depth': child_node['depth'],
            'total_samples': total_samples,
            'path': path[:30],  # Limit path length
            # Thread information
            'thread_id': thread_id,
            'thread_name': thread_name
        }

        # Try to extract Java package/class info
        # 최적화: percentage 제거, duration_ns 추가
        if '.' in child_name and not child_name.endswith('_[k]'):
            parts = child_name.split('.')
            if len(parts) >= 2:
                hot_path_profile['method'] = parts[-1]
                hot_path_profile['class'] = parts[-2]
                if len(parts) > 2:
                    hot_path_profile['package'] = '.'.join(parts[:-2])

        # duration_ns 추가 (최적화)
        hot_path_profile['duration_ns'] = child_node['sample_count'] * 10_000_000

        profiles.append(hot_path_profile)

    return profiles, len(traces)

def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <traces_file> <output_jsonl> [service_name]")
        print()
        print("Example:")
        print(f"  {sys.argv[0]} /tmp/profile-traces.txt /tmp/call-tree.jsonl server1")
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = sys.argv[2]
    service_name = sys.argv[3] if len(sys.argv) > 3 else "server1"

    # Convert
    profiles, traces_count = convert_traces_to_call_tree(input_file, service_name)

    # Write output
    with open(output_file, 'w') as f:
        for profile in profiles:
            f.write(json.dumps(profile) + '\n')

    print(f"Converted {traces_count} traces to call tree")
    if profiles:
        print(f"Total samples: {profiles[0]['total_samples']}")
        print(f"Total unique call paths: {traces_count}")
        print(f"Output: {output_file}")

if __name__ == "__main__":
    main()
