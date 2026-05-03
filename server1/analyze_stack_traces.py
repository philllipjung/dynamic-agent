#!/usr/bin/env python3
"""
Detailed analysis of stack-traces.jsonl
"""

import json
import sys
from collections import defaultdict, Counter
from pathlib import Path

def load_stack_traces(filepath):
    """Load all stack traces from JSONL file"""
    traces = []
    with open(filepath, 'r') as f:
        for line in f:
            if line.strip():
                traces.append(json.loads(line))
    return traces

def analyze_threads(traces):
    """Analyze thread distribution"""
    thread_stats = defaultdict(lambda: {
        'samples': 0,
        'depths': [],
        'max_depth': 0,
        'kernel_frames': 0,
        'java_frames': 0,
        'traces': 0
    })

    for trace in traces:
        thread_name = trace.get('thread_name', 'unknown')
        thread_id = trace.get('thread_id', 0)
        sample_count = trace.get('sample_count', 0)
        stack_depth = trace.get('stack_depth', 0)
        frame_types = trace.get('frame_types', {})

        key = f"{thread_name} (tid={thread_id})"
        thread_stats[key]['samples'] += sample_count
        thread_stats[key]['traces'] += 1
        thread_stats[key]['depths'].append(stack_depth)
        thread_stats[key]['max_depth'] = max(thread_stats[key]['max_depth'], stack_depth)
        thread_stats[key]['kernel_frames'] += frame_types.get('kernel', 0)
        thread_stats[key]['java_frames'] += frame_types.get('java', 0)

    # Calculate average depth
    for stats in thread_stats.values():
        if stats['depths']:
            stats['avg_depth'] = sum(stats['depths']) // len(stats['depths'])
        else:
            stats['avg_depth'] = 0
        del stats['depths']  # Remove temporary list

    return thread_stats

def analyze_top_packages(traces):
    """Analyze most frequent Java packages"""
    package_counter = Counter()

    for trace in traces:
        for frame in trace.get('stack', []):
            package = frame.get('package', '')
            if package and not package.startswith('['):
                package_counter[package] += 1

    return package_counter.most_common(30)

def analyze_top_classes(traces):
    """Analyze most frequent Java classes"""
    class_counter = Counter()

    for trace in traces:
        for frame in trace.get('stack', []):
            clazz = frame.get('class', '')
            if clazz and not clazz.startswith('['):
                class_counter[clazz] += 1

    return class_counter.most_common(30)

def analyze_top_methods(traces):
    """Analyze most frequent methods"""
    method_counter = Counter()

    for trace in traces:
        for frame in trace.get('stack', []):
            method = frame.get('method', '')
            package = frame.get('package', '')
            clazz = frame.get('class', '')
            if method:
                full_name = f"{package}.{clazz}.{method}" if package and clazz else method
                method_counter[full_name] += 1

    return method_counter.most_common(30)

def analyze_kernel_frames(traces):
    """Analyze kernel frame usage"""
    kernel_stats = {
        'total_traces_with_kernel': 0,
        'total_kernel_frames': 0,
        'kernel_frame_types': Counter()
    }

    for trace in traces:
        has_kernel = trace.get('has_kernel_frames', False)
        if has_kernel:
            kernel_stats['total_traces_with_kernel'] += 1

        for frame in trace.get('stack', []):
            if frame.get('is_kernel', False):
                kernel_stats['total_kernel_frames'] += 1
                frame_name = frame.get('name', '').replace('_[k]', '')
                kernel_stats['kernel_frame_types'][frame_name] += 1

    return kernel_stats

def analyze_stack_depth_distribution(traces):
    """Analyze stack depth distribution"""
    depth_distribution = defaultdict(int)

    for trace in traces:
        depth = trace.get('stack_depth', 0)
        # Bucket by ranges
        if depth <= 10:
            bucket = '1-10'
        elif depth <= 20:
            bucket = '11-20'
        elif depth <= 30:
            bucket = '21-30'
        elif depth <= 50:
            bucket = '31-50'
        else:
            bucket = '50+'
        depth_distribution[bucket] += 1

    return depth_distribution

def find_hot_paths(traces, limit=20):
    """Find hottest execution paths"""
    hot_paths = []

    for trace in traces:
        path_frames = []
        for frame in trace.get('stack', []):
            name = frame.get('name', '')
            # Skip thread markers
            if not name.startswith('['):
                path_frames.append(name)

        if path_frames:
            path_str = ' -> '.join(path_frames[:10])  # First 10 frames
            hot_paths.append({
                'path': path_str,
                'samples': trace.get('sample_count', 0),
                'thread': trace.get('thread_name', 'unknown'),
                'depth': trace.get('stack_depth', 0)
            })

    # Sort by sample count
    hot_paths.sort(key=lambda x: x['samples'], reverse=True)
    return hot_paths[:limit]

def analyze_netty_activity(traces):
    """Analyze Netty I/O activity patterns"""
    netty_frames = Counter()
    netty_threads = defaultdict(int)

    for trace in traces:
        thread_name = trace.get('thread_name') or ''
        if thread_name and ('reactor-http' in thread_name or 'netty' in thread_name.lower()):
            netty_threads[thread_name] += trace.get('sample_count', 0)

        for frame in trace.get('stack', []):
            package = frame.get('package', '')
            if package and ('io.netty' in package or 'reactor' in package.lower()):
                class_name = frame.get('class', '')
                method = frame.get('method', '')
                full = f"{package}.{class_name}.{method}"
                netty_frames[full] += 1

    return netty_frames, netty_threads

def print_report(traces):
    """Print comprehensive analysis report"""

    total_traces = len(traces)
    total_samples = sum(t.get('sample_count', 0) for t in traces)
    unique_threads = len(set(f"{t.get('thread_name', '')}:{t.get('thread_id', 0)}" for t in traces))

    print("=" * 80)
    print("JAVA STACK TRACES ANALYSIS REPORT")
    print("=" * 80)
    print()

    print("## OVERVIEW")
    print("-" * 80)
    print(f"Total trace entries:     {total_traces:,}")
    print(f"Total samples collected: {total_samples:,}")
    print(f"Unique threads:          {unique_threads:,}")
    print(f"Avg samples per trace:   {total_samples / total_traces:.2f}")
    print()

    # Thread analysis
    print("## THREAD DISTRIBUTION")
    print("-" * 80)
    thread_stats = analyze_threads(traces)
    sorted_threads = sorted(thread_stats.items(), key=lambda x: x[1]['samples'], reverse=True)

    print(f"{'Thread Name':<40} {'Samples':>10} {'Traces':>8} {'Avg Depth':>10} {'Kernel %':>10}")
    print("-" * 80)
    for thread, stats in sorted_threads[:25]:
        kernel_pct = (stats['kernel_frames'] / (stats['kernel_frames'] + stats['java_frames']) * 100
                      if (stats['kernel_frames'] + stats['java_frames']) > 0 else 0)
        print(f"{thread:<40} {stats['samples']:>10,} {stats['traces']:>8} {stats['avg_depth']:>10} {kernel_pct:>9.1f}%")
    print()

    # Stack depth distribution
    print("## STACK DEPTH DISTRIBUTION")
    print("-" * 80)
    depth_dist = analyze_stack_depth_distribution(traces)
    for bucket in sorted(depth_dist.keys()):
        count = depth_dist[bucket]
        pct = (count / total_traces) * 100
        bar = '#' * int(pct / 2)
        print(f"{bucket:>10} frames: {count:>6} ({pct:>5.1f}%) {bar}")
    print()

    # Top packages
    print("## TOP 30 JAVA PACKAGES")
    print("-" * 80)
    top_packages = analyze_top_packages(traces)
    print(f"{'Package':<60} {'Occurrences':>12}")
    print("-" * 80)
    for package, count in top_packages:
        print(f"{package:<60} {count:>12,}")
    print()

    # Top classes
    print("## TOP 30 JAVA CLASSES")
    print("-" * 80)
    top_classes = analyze_top_classes(traces)
    print(f"{'Class':<60} {'Occurrences':>12}")
    print("-" * 80)
    for clazz, count in top_classes:
        print(f"{clazz:<60} {count:>12,}")
    print()

    # Top methods
    print("## TOP 30 METHODS")
    print("-" * 80)
    top_methods = analyze_top_methods(traces)
    print(f"{'Method':<70} {'Hits':>8}")
    print("-" * 80)
    for method, count in top_methods:
        short_method = method[:70] if len(method) <= 70 else method[:67] + "..."
        print(f"{short_method:<70} {count:>8,}")
    print()

    # Kernel frames
    print("## KERNEL FRAME ANALYSIS")
    print("-" * 80)
    kernel_stats = analyze_kernel_frames(traces)
    kernel_pct = (kernel_stats['total_traces_with_kernel'] / total_traces * 100
                  if total_traces > 0 else 0)
    print(f"Traces with kernel frames: {kernel_stats['total_traces_with_kernel']} ({kernel_pct:.1f}%)")
    print(f"Total kernel frames: {kernel_stats['total_kernel_frames']:,}")
    print()
    print("Top 20 kernel frame types:")
    print(f"{'Frame Name':<50} {'Count':>10}")
    print("-" * 80)
    for frame, count in kernel_stats['kernel_frame_types'].most_common(20):
        print(f"{frame:<50} {count:>10,}")
    print()

    # Netty analysis
    print("## NETTY I/O ACTIVITY")
    print("-" * 80)
    netty_frames, netty_threads = analyze_netty_activity(traces)
    print("Active Netty threads:")
    for thread, samples in sorted(netty_threads.items(), key=lambda x: x[1], reverse=True)[:15]:
        print(f"  {thread:<50} {samples:>8} samples")
    print()
    print("Top 30 Netty/Reactor frames:")
    print(f"{'Frame':<70} {'Count':>8}")
    print("-" * 80)
    for frame, count in netty_frames.most_common(30):
        short_frame = frame[:70] if len(frame) <= 70 else frame[:67] + "..."
        print(f"{short_frame:<70} {count:>8,}")
    print()

    # Hot paths
    print("## TOP 20 HOT EXECUTION PATHS")
    print("-" * 80)
    hot_paths = find_hot_paths(traces, 20)
    for i, path in enumerate(hot_paths, 1):
        print(f"\n#{i}. {path['samples']} samples (Thread: {path['thread']}, Depth: {path['depth']})")
        print(f"    {path['path'][:120]}")
        if len(path['path']) > 120:
            print(f"    ... {path['path'][120:240]}")
    print()

    # JVM internals
    print("## JVM INTERNAL ACTIVITY")
    print("-" * 80)
    jvm_keywords = ['G1', 'Compiler', 'GC', 'JIT', 'Safepoint', 'VM']
    jvm_activity = defaultdict(int)

    for trace in traces:
        thread_name = trace.get('thread_name') or ''
        samples = trace.get('sample_count', 0)
        for keyword in jvm_keywords:
            if keyword in thread_name:
                jvm_activity[keyword] += samples
                break

    print("JVM internal thread activity:")
    for activity, count in sorted(jvm_activity.items(), key=lambda x: x[1], reverse=True):
        print(f"  {activity:<30} {count:>10,} samples")
    print()

    print("=" * 80)
    print("END OF REPORT")
    print("=" * 80)

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <stack-traces.jsonl>")
        sys.exit(1)

    filepath = sys.argv[1]
    traces = load_stack_traces(filepath)
    print_report(traces)
