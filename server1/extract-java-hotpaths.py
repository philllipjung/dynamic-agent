#!/usr/bin/env python3
"""
Extract Java-only hot paths from async-profiler collapsed format
Output format: JSONL with hot_path entries containing Java call stacks
"""

import sys
import json
from datetime import datetime
from collections import defaultdict

def extract_java_paths(collapsed_file, output_file, service_name, top_n=50):
    """
    Extract Java-only hot paths from collapsed format

    Args:
        collapsed_file: Path to collapsed format file
        output_file: Path to output JSONL file
        service_name: Name of the service being profiled
        top_n: Number of top hot paths to extract
    """
    # Parse collapsed format: stack1;stack2;stack3 count
    hot_paths = defaultdict(int)

    with open(collapsed_file, 'r') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('---'):
                continue

            parts = line.rsplit(' ', 1)
            if len(parts) != 2:
                continue

            stack_str, count_str = parts
            try:
                count = int(count_str)
            except ValueError:
                continue

            frames = stack_str.split(';')

            # Filter to Java-only frames (remove kernel and unknown)
            java_frames = [f for f in frames if not f.endswith('_[k]') and not f.startswith('[unknown]')]

            if len(java_frames) < 2:  # Need at least 2 Java frames
                continue

            # Reconstruct the path
            java_path = ';'.join(java_frames)
            hot_paths[java_path] += count

    # Sort by sample count (descending)
    sorted_paths = sorted(hot_paths.items(), key=lambda x: x[1], reverse=True)[:top_n]

    if not sorted_paths:
        print("No Java hot paths found!")
        return 0

    total_samples = sum(count for _, count in sorted_paths)

    # Generate JSONL output
    timestamp = datetime.utcnow().isoformat() + 'Z'

    with open(output_file, 'w') as f:
        for path_str, sample_count in sorted_paths:
            frames = path_str.split(';')

            # Extract Java package/class info from frames
            java_packages = set()
            java_classes = set()

            for frame in frames:
                if '.' in frame and not frame.startswith('['):
                    parts = frame.split('.')
                    if len(parts) >= 2:
                        class_parts = '.'.join(parts[:-1])
                        last_dot = class_parts.rfind('.')
                        if last_dot > 0:
                            package = class_parts[:last_dot]
                            clazz = class_parts[last_dot + 1:]
                            java_packages.add(package)
                            java_classes.add(clazz)

            entry = {
                "timestamp": timestamp,
                "service": service_name,
                "profiler": "async-profiler",
                "profiler_version": "4.1",
                "profile_type": "hot_path",
                "path_name": frames[0] if frames else "unknown",
                "sample_count": sample_count,
                "self_samples": sample_count,
                "percentage": round((sample_count / total_samples) * 100, 2),
                "depth": len(frames),
                "total_samples": total_samples,
                "path": frames[:20],  # Limit path length
                "java_packages": sorted(list(java_packages)),
                "java_classes": sorted(list(java_classes))
            }

            f.write(json.dumps(entry) + '\n')

    print(f"Extracted {len(sorted_paths)} Java hot paths")
    print(f"Total Java samples: {total_samples}")
    print(f"Unique packages: {len(java_packages)}")
    print(f"Unique classes: {len(java_classes)}")
    print(f"Output: {output_file}")

    return len(sorted_paths)

if __name__ == "__main__":
    if len(sys.argv) < 4:
        print("Usage: extract-java-hotpaths.py <collapsed_file> <output_file> <service_name> [top_n]")
        sys.exit(1)

    collapsed_file = sys.argv[1]
    output_file = sys.argv[2]
    service_name = sys.argv[3]
    top_n = int(sys.argv[4]) if len(sys.argv) > 4 else 50

    extract_java_paths(collapsed_file, output_file, service_name, top_n)
