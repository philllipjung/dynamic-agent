#!/usr/bin/env python3
"""
Detailed analysis of call-trees.jsonl
Aggregates call tree and hot path data from async-profiler
"""

import json
import sys
from collections import defaultdict, Counter
from pathlib import Path

def load_call_trees(filepath):
    """Load all call tree entries from JSONL file"""
    entries = []
    with open(filepath, 'r') as f:
        for line in f:
            if line.strip():
                entries.append(json.loads(line))
    return entries

def analyze_call_tree_roots(entries):
    """Analyze main call tree entries"""
    call_trees = [e for e in entries if e.get('profile_type') == 'call_tree']
    hot_paths = [e for e in entries if e.get('profile_type') == 'hot_path']

    return call_trees, hot_paths

def extract_node_statistics(tree_node, depth=0, max_depth=100):
    """Recursively extract statistics from call tree node"""
    stats = {
        'total_nodes': 1,
        'max_depth': depth,
        'total_samples': tree_node.get('sample_count', 0),
        'leaf_nodes': 0,
        'branch_nodes': 0,
        'depth_distribution': defaultdict(int)
    }

    stats['depth_distribution'][depth] += 1

    children = tree_node.get('children', [])
    if not children:
        stats['leaf_nodes'] = 1
    else:
        stats['branch_nodes'] = 1
        for child in children:
            child_stats = extract_node_statistics(child, depth + 1, max_depth)
            stats['total_nodes'] += child_stats['total_nodes']
            stats['max_depth'] = max(stats['max_depth'], child_stats['max_depth'])
            for d, count in child_stats['depth_distribution'].items():
                stats['depth_distribution'][d] += count
            stats['leaf_nodes'] += child_stats['leaf_nodes']
            stats['branch_nodes'] += child_stats['branch_nodes']

    return stats

def analyze_hot_paths(hot_paths):
    """Analyze hot path entries"""
    path_stats = defaultdict(lambda: {
        'count': 0,
        'total_samples': 0,
        'total_self_samples': 0,
        'threads': set(),
        'paths': []
    })

    for path in hot_paths:
        path_name = path.get('path_name', 'unknown')
        samples = path.get('sample_count', 0)
        self_samples = path.get('self_samples', 0)
        thread = path.get('thread_name', 'unknown')

        path_stats[path_name]['count'] += 1
        path_stats[path_name]['total_samples'] += samples
        path_stats[path_name]['total_self_samples'] += self_samples
        path_stats[path_name]['threads'].add(thread)
        path_stats[path_name]['paths'].append(path.get('path', [])[:10])

    return path_stats

def find_execution_patterns(entries):
    """Find common execution patterns"""
    patterns = Counter()

    for entry in entries:
        if entry.get('profile_type') == 'hot_path':
            path = entry.get('path', [])
            # Look for 3-frame sequences
            for i in range(len(path) - 2):
                pattern = ' -> '.join(path[i:i+3])
                patterns[pattern] += 1

    return patterns

def analyze_java_packages(entries):
    """Analyze Java package distribution"""
    package_counter = Counter()
    package_samples = defaultdict(int)

    for entry in entries:
        packages = entry.get('java_packages', [])
        total_samples = entry.get('total_samples', 0)

        for pkg in packages:
            package_counter[pkg] += 1
            package_samples[pkg] += total_samples

    return package_counter, package_samples

def analyze_thread_distribution(entries):
    """Analyze samples by thread"""
    thread_stats = defaultdict(lambda: {
        'total_samples': 0,
        'tree_count': 0,
        'hot_path_count': 0
    })

    for entry in entries:
        if entry.get('profile_type') == 'call_tree':
            thread = entry.get('call_tree', {}).get('thread_name', 'unknown')
            samples = entry.get('total_samples', 0)
            thread_stats[thread]['total_samples'] += samples
            thread_stats[thread]['tree_count'] += 1
        elif entry.get('profile_type') == 'hot_path':
            thread = entry.get('thread_name', 'unknown')
            samples = entry.get('sample_count', 0)
            thread_stats[thread]['total_samples'] += samples
            thread_stats[thread]['hot_path_count'] += 1

    return thread_stats

def print_report(entries):
    """Print comprehensive analysis report"""

    call_trees, hot_paths = analyze_call_tree_roots(entries)

    print("=" * 80)
    print("JAVA CALL TREES ANALYSIS REPORT")
    print("=" * 80)
    print()

    print("## OVERVIEW")
    print("-" * 80)
    print(f"Total entries:            {len(entries):,}")
    print(f"  - Call tree entries:    {len(call_trees):,}")
    print(f"  - Hot path entries:     {len(hot_paths):,}")
    print()

    if call_trees:
        total_samples = sum(ct.get('total_samples', 0) for ct in call_trees)
        total_traces = sum(ct.get('total_traces', 0) for ct in call_trees)
        total_packages = sum(ct.get('unique_packages', 0) for ct in call_trees)
        total_classes = sum(ct.get('unique_classes', 0) for ct in call_trees)

        print(f"Aggregated Statistics:")
        print(f"  Total samples:           {total_samples:,}")
        print(f"  Total traces:            {total_traces:,}")
        print(f"  Unique packages:         {total_packages:,}")
        print(f"  Unique classes:          {total_classes:,}")
        print()

    # Call tree structure analysis
    print("## CALL TREE STRUCTURE ANALYSIS")
    print("-" * 80)
    all_tree_stats = []
    for ct in call_trees:
        tree = ct.get('call_tree', {})
        if tree:
            stats = extract_node_statistics(tree)
            stats['thread'] = tree.get('thread_name', 'unknown')
            all_tree_stats.append(stats)

    if all_tree_stats:
        total_nodes = sum(s['total_nodes'] for s in all_tree_stats)
        total_leaves = sum(s['leaf_nodes'] for s in all_tree_stats)
        total_branches = sum(s['branch_nodes'] for s in all_tree_stats)
        max_depth = max(s['max_depth'] for s in all_tree_stats)

        print(f"Total tree nodes:          {total_nodes:,}")
        print(f"  - Branch nodes:          {total_branches:,} ({total_branches/total_nodes*100:.1f}%)")
        print(f"  - Leaf nodes:            {total_leaves:,} ({total_leaves/total_nodes*100:.1f}%)")
        print(f"Maximum tree depth:        {max_depth}")
        print()

        # Depth distribution
        print("Tree depth distribution (aggregated):")
        all_depths = defaultdict(int)
        for stats in all_tree_stats:
            for depth, count in stats['depth_distribution'].items():
                all_depths[depth] += count

        for depth in sorted(all_depths.keys())[:15]:
            count = all_depths[depth]
            pct = (count / total_nodes) * 100
            bar = '#' * int(pct / 2)
            print(f"  Depth {depth:>3}: {count:>8} nodes ({pct:>5.1f}%) {bar}")
        if len(all_depths) > 15:
            print(f"  ... ({len(all_depths) - 15} more depth levels)")
        print()

    # Thread distribution
    print("## THREAD DISTRIBUTION")
    print("-" * 80)
    thread_stats = analyze_thread_distribution(entries)

    print(f"{'Thread':<35} {'Samples':>12} {'Trees':>8} {'Hot Paths':>10}")
    print("-" * 80)
    for thread, stats in sorted(thread_stats.items(),
                                  key=lambda x: x[1]['total_samples'] if x[1] else 0,
                                  reverse=True)[:20]:
        thread_name = thread if thread else 'unknown'
        total_samples = stats['total_samples'] if stats['total_samples'] else 0
        tree_count = stats['tree_count'] if stats['tree_count'] else 0
        hot_path_count = stats['hot_path_count'] if stats['hot_path_count'] else 0
        print(f"{thread_name:<35} {total_samples:>12,} "
              f"{tree_count:>8} {hot_path_count:>10}")
    print()

    # Hot paths analysis
    print("## TOP 30 HOT PATHS (by sample count)")
    print("-" * 80)
    path_stats = analyze_hot_paths(hot_paths)

    print(f"{'Path Name':<50} {'Samples':>10} {'Self':>8} {'Hits':>6} {'Threads':>6}")
    print("-" * 80)
    for path_name, stats in sorted(path_stats.items(),
                                    key=lambda x: x[1]['total_samples'],
                                    reverse=True)[:30]:
        samples = stats['total_samples']
        self_samples = stats['total_self_samples']
        hits = stats['count']
        threads = len(stats['threads'])
        short_name = path_name[:47] + '...' if len(path_name) > 50 else path_name
        print(f"{short_name:<50} {samples:>10,} {self_samples:>8,} {hits:>6} {threads:>6}")
    print()

    # Java packages
    print("## TOP 30 JAVA PACKAGES (by occurrence)")
    print("-" * 80)
    package_counter, package_samples = analyze_java_packages(call_trees)

    print(f"{'Package':<60} {'Trees':>8} {'Samples':>10}")
    print("-" * 80)
    for pkg, count in package_counter.most_common(30):
        samples = package_samples.get(pkg, 0)
        print(f"{pkg:<60} {count:>8} {samples:>10,}")
    print()

    # Execution patterns
    print("## TOP 20 EXECUTION PATTERNS (3-frame sequences)")
    print("-" * 80)
    patterns = find_execution_patterns(hot_paths)

    print(f"{'Pattern':<70} {'Count':>6}")
    print("-" * 80)
    for pattern, count in patterns.most_common(20):
        short_pattern = pattern[:67] + '...' if len(pattern) > 70 else pattern
        print(f"{short_pattern:<70} {count:>6,}")
    print()

    # Self-samples analysis (where CPU time is actually spent)
    print("## TOP 30 METHODS BY SELF-SAMPLES")
    print("-" * 80)

    # Aggregate self-samples from hot paths
    self_samples = defaultdict(int)

    for hp in hot_paths:
        path_name = hp.get('path_name', 'unknown')
        self_samples[path_name] += hp.get('self_samples', 0)

    print(f"{'Method/Frame':<60} {'Self Samples':>15}")
    print("-" * 80)
    for method, samples in sorted(self_samples.items(),
                                   key=lambda x: x[1],
                                   reverse=True)[:30]:
        short_method = method[:57] + '...' if len(method) > 60 else method
        print(f"{short_method:<60} {samples:>15,}")
    print()

    # Call tree complexity by thread
    print("## CALL TREE COMPLEXITY BY THREAD")
    print("-" * 80)

    thread_complexity = {}
    for ct in call_trees:
        tree = ct.get('call_tree', {})
        if tree:
            thread = tree.get('thread_name', 'unknown')
            stats = extract_node_statistics(tree)
            if thread not in thread_complexity:
                thread_complexity[thread] = {
                    'nodes': 0,
                    'max_depth': 0,
                    'leaves': 0,
                    'branches': 0,
                    'samples': 0
                }
            thread_complexity[thread]['nodes'] += stats['total_nodes']
            thread_complexity[thread]['max_depth'] = max(
                thread_complexity[thread]['max_depth'],
                stats['max_depth']
            )
            thread_complexity[thread]['leaves'] += stats['leaf_nodes']
            thread_complexity[thread]['branches'] += stats['branch_nodes']
            thread_complexity[thread]['samples'] += tree.get('sample_count', 0)

    print(f"{'Thread':<35} {'Nodes':>10} {'Depth':>6} {'Leaves':>10} {'Branches':>10} {'Samples':>10}")
    print("-" * 80)
    for thread, stats in sorted(thread_complexity.items(),
                                  key=lambda x: x[1]['samples'],
                                  reverse=True)[:15]:
        print(f"{thread:<35} {stats['nodes']:>10,} {stats['max_depth']:>6} "
              f"{stats['leaves']:>10,} {stats['branches']:>10,} {stats['samples']:>10,}")
    print()

    # Hot path depth distribution
    print("## HOT PATH DEPTH DISTRIBUTION")
    print("-" * 80)

    depth_buckets = defaultdict(int)
    for hp in hot_paths:
        depth = hp.get('depth', 0)
        if depth <= 5:
            bucket = '1-5'
        elif depth <= 10:
            bucket = '6-10'
        elif depth <= 20:
            bucket = '11-20'
        elif depth <= 30:
            bucket = '21-30'
        else:
            bucket = '30+'
        depth_buckets[bucket] += 1

    print(f"{'Depth Range':<15} {'Count':>10} {'Percentage':>12}")
    print("-" * 80)
    total_hot_paths = len(hot_paths)
    for bucket in ['1-5', '6-10', '11-20', '21-30', '30+']:
        count = depth_buckets.get(bucket, 0)
        pct = (count / total_hot_paths * 100) if total_hot_paths > 0 else 0
        bar = '#' * int(pct / 2)
        print(f"{bucket:<15} {count:>10} {pct:>11.1f}% {bar}")
    print()

    # Sample concentration analysis
    print("## SAMPLE CONCENTRATION ANALYSIS")
    print("-" * 80)

    # Sort hot paths by samples and calculate concentration
    sorted_paths = sorted(hot_paths, key=lambda x: x.get('sample_count', 0), reverse=True)
    total_samples = sum(hp.get('sample_count', 0) for hp in hot_paths)

    print("Top paths contributing to 50% of samples:")
    cumulative = 0
    for i, hp in enumerate(sorted_paths, 1):
        samples = hp.get('sample_samples', 0)
        cumulative += samples
        if cumulative <= total_samples * 0.5:
            path_name = hp.get('path_name', 'unknown')[:50]
            thread = hp.get('thread_name', 'unknown')
            print(f"  {i:>2}. {path_name:<50} ({thread})")

        if cumulative >= total_samples * 0.5 and i > 1:
            break
    print()

    print("=" * 80)
    print("END OF REPORT")
    print("=" * 80)

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <call-trees.jsonl>")
        sys.exit(1)

    filepath = sys.argv[1]
    entries = load_call_trees(filepath)
    print_report(entries)
