#!/usr/bin/env python3
"""
JSON 스키마 검증 도구
stack-traces.jsonl과 call-trees.jsonl의 스키마를 검증합니다.
"""

import json
import sys
from typing import Tuple, List, Dict, Any


def validate_stack_trace(record: Dict[str, Any]) -> Tuple[bool, str]:
    """
    stack-traces.jsonl v2 레코드 검증

    Args:
        record: JSON 객체

    Returns:
        (유효여부, 에러메시지)
    """
    # v2 필수 필드 (최적화됨)
    required_fields = [
        'service', 'profiler', 'profiler_version', 'timestamp',
        'thread_id', 'thread_name', 'sample_count', 'stack_depth',
        'frame_types', 'stack'
    ]

    for field in required_fields:
        if field not in record:
            return False, f"필수 필드 누락: {field}"

    # v2: 제거된 필드가 존재하면 경고
    deprecated_fields = ['top_frame', 'bottom_frame', 'has_kernel_frames',
                         'has_java_frames', 'java_packages', 'java_classes']
    for field in deprecated_fields:
        if field in record:
            return False, f"v2에서 제거된 필드 존재: {field} (stack 또는 frame_types를 사용하세요)"

    # 타입 검증
    if not isinstance(record['timestamp'], str):
        return False, "timestamp는 문자열이어야 합니다"

    if not isinstance(record['service'], str):
        return False, "service는 문자열이어야 합니다"

    if not isinstance(record['stack'], list):
        return False, "stack는 배열이어야 합니다"

    if not isinstance(record['stack_depth'], int):
        return False, "stack_depth는 정수이어야 합니다"

    if not isinstance(record['sample_count'], int):
        return False, "sample_count는 정수이어야 합니다"

    if not isinstance(record['thread_id'], int):
        return False, "thread_id는 정수이어야 합니다"

    # 스택 깊이 일치 검증
    if len(record['stack']) != record['stack_depth']:
        return False, f"stack_depth({record['stack_depth']})가 실제 스택 길이({len(record['stack'])}와 일치하지 않습니다"

    # frame_types 검증
    frame_types = record['frame_types']
    required_types = ['kernel', 'java', 'unknown', 'native']
    for ft in required_types:
        if ft not in frame_types:
            return False, f"frame_types에 {ft}가 누락되었습니다"

    # 스택 프레임 검증
    for i, frame in enumerate(record['stack']):
        if not isinstance(frame, dict):
            return False, f"stack[{i}]는 객체여야 합니다"

        frame_required = ['raw', 'name', 'type', 'is_kernel']
        for field in frame_required:
            if field not in frame:
                return False, f"stack[{i}]에 필수 필드 {field}가 누락되었습니다"

        if frame['type'] not in ['java', 'kernel', 'native', 'unknown']:
            return False, f"stack[{i}].type이 잘못되었습니다: {frame['type']}"

    return True, "유효함"


def validate_call_tree(record: Dict[str, Any]) -> Tuple[bool, str]:
    """
    call-trees.jsonl 레코드 검증

    Args:
        record: JSON 객체

    Returns:
        (유효여부, 에러메시지)
    """
    # 기본 필수 필드
    required_fields = [
        'timestamp', 'service', 'profiler', 'profiler_version',
        'profile_type'
    ]

    for field in required_fields:
        if field not in record:
            return False, f"필수 필드 누락: {field}"

    # profile_type에 따른 검증
    profile_type = record['profile_type']

    if profile_type == 'call_tree':
        return validate_call_tree_root(record)
    elif profile_type == 'hot_path':
        return validate_hot_path(record)
    else:
        return False, f"잘못된 profile_type: {profile_type} (call_tree 또는 hot_path여야 함)"


def validate_call_tree_root(record: Dict[str, Any]) -> Tuple[bool, str]:
    """Call Tree Root 레코드 검증"""
    required_fields = [
        'total_samples', 'total_traces', 'unique_packages',
        'unique_classes', 'java_packages', 'java_classes', 'call_tree'
    ]

    for field in required_fields:
        if field not in record:
            return False, f"Call Tree 필수 필드 누락: {field}"

    # 타입 검증
    if not isinstance(record['total_samples'], int):
        return False, "total_samples는 정수여야 합니다"

    if not isinstance(record['java_packages'], list):
        return False, "java_packages는 배열이어야 합니다"

    if not isinstance(record['java_classes'], list):
        return False, "java_classes는 배열이어야 합니다"

    if not isinstance(record['call_tree'], dict):
        return False, "call_tree는 객체여야 합니다"

    # TreeNode 검증
    tree = record['call_tree']
    tree_required = ['name', 'sample_count', 'self_samples', 'percentage', 'depth', 'path']
    for field in tree_required:
        if field not in tree:
            return False, f"call_tree.{field}가 누락되었습니다"

    if tree['depth'] != 0:
        return False, f"Call Tree Root의 depth는 0이어야 합니다 (현재: {tree['depth']})"

    if tree['path'] != []:
        return False, f"Call Tree Root의 path는 비어있어야 합니다 (현재: {tree['path']})"

    return True, "유효한 Call Tree Root"


def validate_hot_path(record: Dict[str, Any]) -> Tuple[bool, str]:
    """Hot Path 레코드 검증 (v2)"""
    required_fields = [
        'path_name', 'sample_count', 'self_samples', 'percentage',
        'depth', 'total_samples', 'path', 'duration_ns'  # v2: duration_ns 필수
    ]

    for field in required_fields:
        if field not in record:
            return False, f"Hot Path 필수 필드 누락: {field}"

    # 타입 검증
    if not isinstance(record['path_name'], str):
        return False, "path_name은 문자열이어야 합니다"

    if not isinstance(record['sample_count'], int):
        return False, "sample_count는 정수여야 합니다"

    if not isinstance(record['self_samples'], int):
        return False, "self_samples는 정수여야 합니다"

    if not isinstance(record['percentage'], (int, float)):
        return False, "percentage는 숫자여야 합니다"

    if not isinstance(record['depth'], int):
        return False, "depth는 정수여야 합니다"

    if not isinstance(record['path'], list):
        return False, "path는 배열이어야 합니다"

    # v2: duration_ns 필수 필드
    if not isinstance(record['duration_ns'], int):
        return False, "duration_ns는 정수여야 합니다"

    # 논리 검증
    if record['self_samples'] > record['sample_count']:
        return False, f"self_samples({record['self_samples']})가 sample_count({record['sample_count']})보다 클 수 없습니다"

    if record['depth'] != len(record['path']):
        return False, f"depth({record['depth']})와 path 길이({len(record['path'])}가 일치하지 않습니다"

    if record['percentage'] < 0 or record['percentage'] > 100:
        return False, f"percentage({record['percentage']})는 0-100 사이여야 합니다"

    return True, "유효한 Hot Path"


def analyze_file(filepath: str, file_type: str) -> Dict[str, Any]:
    """
    파일 분석 및 검증

    Args:
        filepath: JSONL 파일 경로
        file_type: 'stack-traces' 또는 'call-trees'

    Returns:
        분석 결과 dict
    """
    results = {
        'total_records': 0,
        'valid_records': 0,
        'invalid_records': 0,
        'errors': [],
        'statistics': {}
    }

    validator = validate_stack_trace if file_type == 'stack-traces' else validate_call_tree

    with open(filepath, 'r', encoding='utf-8') as f:
        for line_num, line in enumerate(f, 1):
            if not line.strip():
                continue

            results['total_records'] += 1

            try:
                record = json.loads(line)
                is_valid, message = validator(record)

                if is_valid:
                    results['valid_records'] += 1
                else:
                    results['invalid_records'] += 1
                    results['errors'].append({
                        'line': line_num,
                        'error': message
                    })
            except json.JSONDecodeError as e:
                results['invalid_records'] += 1
                results['errors'].append({
                    'line': line_num,
                    'error': f"JSON 파싱 오류: {e}"
                })

    # 통계 계산
    if results['total_records'] > 0:
        results['statistics']['validation_rate'] = \
            (results['valid_records'] / results['total_records']) * 100

    return results


def print_report(results: Dict[str, Any], file_type: str):
    """검증 보고서 출력"""
    print("=" * 80)
    print(f"JSON 스키마 검증 보고서: {file_type}.jsonl")
    print("=" * 80)
    print()

    print("## 개요")
    print("-" * 80)
    print(f"총 레코드 수:   {results['total_records']:,}")
    print(f"유효한 레코드:   {results['valid_records']:,}")
    print(f"유효하지 않은:   {results['invalid_records']:,}")

    if 'validation_rate' in results['statistics']:
        print(f"검증 통과율:   {results['statistics']['validation_rate']:.2f}%")
    print()

    if results['errors']:
        print("## 오류 목록")
        print("-" * 80)
        for error in results['errors'][:20]:  # 처음 20개만 표시
            print(f"  라인 {error['line']}: {error['error']}")

        if len(results['errors']) > 20:
            print(f"  ... 그 외 {len(results['errors']) - 20}개 오류")
        print()
    else:
        print("✅ 모든 레코드가 유효합니다!")
        print()

    print("=" * 80)


def main():
    if len(sys.argv) < 2:
        print("사용법: python3 validate_json_schema.py <stack-traces|call-trees>")
        print()
        print("예시:")
        print("  python3 validate_json_schema.py stack-traces")
        print("  python3 validate_json_schema.py call-trees")
        sys.exit(1)

    file_type = sys.argv[1]

    if file_type == 'stack-traces':
        filepath = '/root/webflux-demo/server1/stack-traces.jsonl'
    elif file_type == 'call-trees':
        filepath = '/root/webflux-demo/server1/call-trees.jsonl'
    else:
        print(f"오류: 잘못된 파일 타입 '{file_type}'")
        print("지원되는 타입: stack-traces, call-trees")
        sys.exit(1)

    # 파일 분석
    results = analyze_file(filepath, file_type)

    # 보고서 출력
    print_report(results, file_type)

    # 종료 코드
    sys.exit(0 if results['invalid_records'] == 0 else 1)


if __name__ == '__main__':
    main()
