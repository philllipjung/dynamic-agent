#!/usr/bin/env python3
"""
Enhanced async-profiler to JSON converter for OpenTelemetry/OpenSearch
Converts multiple async-profiler output formats to rich JSON with detailed profiling data
"""

import sys
import json
import datetime
import re
from pathlib import Path
from collections import defaultdict

def parse_jfr_file(jfr_file):
    """
    Parse JFR file and extract detailed profiling information
    Note: This requires jfrconv tool or JFR parsing library
    """
    # For now, we'll use the collapsed format with enhanced metadata
    pass

def extract_thread_info(frames):
    """
    Extract thread ID and name from stack frames
    Thread format: [thread-name tid=12345]
    Returns (thread_id, thread_name) tuple
    """
    thread_id = None
    thread_name = None

    for frame in frames:
        raw = frame.get("raw", "")
        # Match pattern: [thread-name tid=12345]
        match = re.search(r'\[(.+?)\s+tid=(\d+)\]', raw)
        if match:
            thread_name = match.group(1)
            thread_id = int(match.group(2))
            break

    return thread_id, thread_name

def parse_collapsed_with_thread_info(input_file, service_name="server1"):
    """
    Parse collapsed output and extract as much detail as possible
    Enhanced to include method signatures, package info, and frame types
    """
    timestamp = datetime.datetime.utcnow().isoformat() + "Z"
    profiles = []

    with open(input_file, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue

            parts = line.rsplit(' ', 1)
            if len(parts) != 2:
                continue

            stack_str, count = parts
            frames = stack_str.split(';')

            # Enhanced frame analysis
            enhanced_frames = []
            for frame in frames:
                # Skip [unknown] frames
                if frame == "[unknown]":
                    continue

                frame_info = enhance_frame_info(frame)
                enhanced_frames.append(frame_info)

            # Extract thread info
            thread_id, thread_name = extract_thread_info(enhanced_frames)

            # 최적화된 필드 순서: 메타데이터 → 큰 데이터(stack)
            profile = {
                # 1. 필수 메타데이터 (빠른 접근)
                "service": service_name,
                "profiler": "async-profiler",
                "profiler_version": "4.1",
                "timestamp": timestamp,
                "thread_id": thread_id,
                "thread_name": thread_name,

                # 2. 샘플 메트릭
                "sample_count": int(count),
                "stack_depth": len(enhanced_frames),
                "frame_types": analyze_frame_types(enhanced_frames),

                # 3. 큰 데이터 (마지막에 배치)
                "stack": enhanced_frames
            }

            # 추가 메타데이터 (현재는 비어있음)
            profile.update(extract_metadata(enhanced_frames))

            profiles.append(profile)

    return profiles

def enhance_frame_info(frame):
    """
    Extract detailed information from a stack frame
    """
    frame_info = {
        "raw": frame,
        "name": frame,
    }

    # Detect kernel frames
    if frame.endswith("_[k]"):
        frame_info["type"] = "kernel"
        frame_info["name"] = frame.replace("_[k]", "")
        frame_info["is_kernel"] = True
    else:
        frame_info["type"] = "java"
        frame_info["is_kernel"] = False

        # Parse Java method signature
        # Format: package.Class.method or package.Class$Inner.method
        if "." in frame:
            parts = frame.split(".")
            if len(parts) >= 2:
                method = parts[-1]
                class_parts = ".".join(parts[:-1])

                # Extract package and class
                if "/" in class_parts:
                    package, clazz = class_parts.rsplit("/", 1)
                else:
                    # Package.Class format
                    last_dot = class_parts.rfind(".")
                    if last_dot > 0:
                        package = class_parts[:last_dot]
                        clazz = class_parts[last_dot + 1:]
                    else:
                        package = ""
                        clazz = class_parts

                frame_info["method"] = method
                frame_info["class"] = clazz
                frame_info["package"] = package

                # Detect special methods
                if method.startswith("<"):
                    frame_info["is_special"] = True
                    frame_info["method_type"] = method

    return frame_info

def analyze_frame_types(frames):
    """Analyze frame types in the stack"""
    types = {
        "kernel": 0,
        "java": 0,
        "unknown": 0,
        "native": 0,
    }

    for frame in frames:
        frame_type = frame.get("type", "unknown")
        if frame_type in types:
            types[frame_type] += 1
        else:
            types["unknown"] += 1

    return types

def extract_metadata(frames):
    """
    Extract additional metadata from the stack trace (최적화됨)

    제거된 필드 (중복):
    - has_kernel_frames: frame_types.kernel > 0으로 계산 가능
    - has_java_frames: frame_types.java > 0으로 계산 가능
    - top_frame: stack[0]로 대체 가능
    - bottom_frame: stack[-1]로 대체 가능
    - java_packages: stack에서 추출 가능
    - java_classes: stack에서 추출 가능
    """
    metadata = {}  # 빈 메타데이터 (최적화됨)

    return metadata

def convert_flat_profile(input_file, service_name="server1"):
    """
    Convert flat profile output to JSON (includes method-level statistics)
    Format: method_name total_samples [samples_1] [samples_2] ...
    Skips header lines and summary lines
    """
    timestamp = datetime.datetime.utcnow().isoformat() + "Z"
    method_stats = []

    with open(input_file, 'r') as f:
        for line in f:
            line = line.strip()
            # Skip empty lines, comments, headers, and summary lines
            if not line or line.startswith("#") or line.startswith("---"):
                continue
            # Skip lines that don't start with a method/class name
            if line[0].isdigit() or line.startswith("Total") or line.startswith("Execution"):
                continue

            parts = line.split()
            if len(parts) < 2:
                continue

            # First part is the method name (may contain spaces in package/class)
            # Last part is the sample count
            try:
                # Find the last numeric value (sample count)
                for i in range(len(parts) - 1, 0, -1):
                    try:
                        total_samples = int(parts[i])
                        # Everything before this is the method name
                        method_name = " ".join(parts[:i])
                        break
                    except ValueError:
                        continue
                else:
                    continue
            except (ValueError, IndexError):
                continue

            stat = {
                "timestamp": timestamp,
                "service": service_name,
                "profiler": "async-profiler",
                "profile_type": "flat",
                "method": method_name,
                "total_samples": total_samples,
            }

            # Enhance method info
            frame_info = enhance_frame_info(method_name)
            stat["method_info"] = frame_info

            method_stats.append(stat)

    return method_stats

def convert_tree_profile(input_file, service_name="server1"):
    """
    Convert tree profile output to JSON (call tree structure)
    Format: indentation-based tree with percentages and samples
    """
    timestamp = datetime.datetime.utcnow().isoformat() + "Z"
    root_nodes = []
    stack = []

    with open(input_file, 'r') as f:
        for line in f:
            line = line.rstrip()
            # Skip empty lines, headers, and separators
            if not line or line.startswith("#") or line.startswith("---"):
                continue

            # Calculate depth from indentation (tabs or spaces)
            indent_match = re.match(r'(\s*)', line)
            indent = len(indent_match.group(1)) if indent_match else 0

            rest_of_line = line.lstrip()

            # Extract frame info, percentage, and samples
            # Format: "frame_name percentage% samples" or "frame_name percentage%"
            match = re.match(r'^(.*?)\s+(\d+\.?\d*)%?\s*(\d+)?\s*$', rest_of_line)
            if match:
                frame_name = match.group(1).strip()
                percentage = float(match.group(2)) if match.group(2) else 0
                samples_str = match.group(3)
                samples = int(samples_str) if samples_str else 0

                # Calculate depth based on indentation
                depth = indent // 2 if indent > 0 else 0

                node = {
                    "frame": frame_name,
                    "percentage": percentage,
                    "samples": samples,
                    "depth": depth,
                    "frame_info": enhance_frame_info(frame_name),
                    "children": []
                }

                # Adjust stack to correct depth
                while len(stack) > depth:
                    stack.pop()

                # Add to parent or root
                if stack:
                    stack[-1]["children"].append(node)
                else:
                    root_nodes.append(node)

                stack.append(node)

    return [{
        "timestamp": timestamp,
        "service": service_name,
        "profiler": "async-profiler",
        "profile_type": "tree",
        "call_tree": root_nodes
    }]

def main():
    if len(sys.argv) < 4:
        print("Usage: python3 async-profiler-enhanced.py <format> <input_file> <output_file> [service_name]")
        print()
        print("Formats:")
        print("  collapsed  - Collapsed stack traces with enhanced frame info")
        print("  flat       - Flat profile with method statistics")
        print("  tree       - Call tree structure")
        print()
        print("Example:")
        print("  python3 async-profiler-enhanced.py collapsed profile.txt output.jsonl server1")
        sys.exit(1)

    format_type = sys.argv[1].lower()
    input_file = sys.argv[2]
    output_file = sys.argv[3]
    service_name = sys.argv[4] if len(sys.argv) > 4 else "server1"

    profiles = []

    if format_type == "collapsed":
        profiles = parse_collapsed_with_thread_info(input_file, service_name)
    elif format_type == "flat":
        profiles = convert_flat_profile(input_file, service_name)
    elif format_type == "tree":
        profiles = convert_tree_profile(input_file, service_name)
    else:
        print(f"Unknown format: {format_type}")
        sys.exit(1)

    # Write to output file in JSONL format
    with open(output_file, 'w') as f:
        for profile in profiles:
            f.write(json.dumps(profile) + '\n')

    print(f"Converted {len(profiles)} profile entries from {input_file} to {output_file}")
    print(f"Format: {format_type}")

if __name__ == "__main__":
    main()
