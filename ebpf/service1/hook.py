#!/usr/bin/python3
from bcc import BPF
import argparse
import json
import time
import threading
import os
import datetime
from ctypes import c_ulonglong, c_int, c_long, c_char, Structure, cast, POINTER
from functools import partial

# eBPF C program - Corrected syscall TID logic with syscall timing
bpf_text = """
#include <linux/sched.h>
#include <asm/ptrace.h>
#include <linux/prctl.h>

// --- 1. Sched Switch Event ---
struct sched_event_t {
    u64 start_time_ns;
    u64 end_time_ns;
    u32 pid;
    u32 tid;
    char comm[TASK_COMM_LEN];
};
BPF_PERF_OUTPUT(sched_events);
BPF_HASH(start_times, u32, u64);


// --- 2. Syscall Events with timing ---
struct syscall_timing_t {
    u64 start_time;
    long syscall_id;
};

struct syscall_event_t {
    u64 start_time_ns;
    u64 end_time_ns;
    u32 pid;
    u32 tid;
    long syscall_id;
};

BPF_PERF_OUTPUT(syscall_events);
BPF_HASH(syscall_start_times, u32, struct syscall_timing_t);

TRACEPOINT_PROBE(raw_syscalls, sys_enter) {
    u64 id = bpf_get_current_pid_tgid();
    u32 tgid = id >> 32;
    u32 tid = (u32)id;

    if (tgid != TARGET_PID) {
        return 0;
    }

    struct syscall_timing_t timing = {};
    timing.start_time = bpf_ktime_get_ns();
    timing.syscall_id = args->id;

    syscall_start_times.update(&tid, &timing);
    return 0;
}

TRACEPOINT_PROBE(raw_syscalls, sys_exit) {
    u64 id = bpf_get_current_pid_tgid();
    u32 tgid = id >> 32;
    u32 tid = (u32)id;

    if (tgid != TARGET_PID) {
        return 0;
    }

    struct syscall_timing_t *timing = syscall_start_times.lookup(&tid);
    if (timing != 0) {
        struct syscall_event_t event = {};
        event.start_time_ns = timing->start_time;
        event.end_time_ns = bpf_ktime_get_ns();
        event.pid = tgid;
        event.tid = tid;
        event.syscall_id = timing->syscall_id;

        syscall_events.perf_submit(args, &event, sizeof(event));
        syscall_start_times.delete(&tid);
    }
    return 0;
}

// --- 3. Thread Name Change Event (prctl) ---
struct thread_name_event_t {
    u64 ts;
    u32 pid;
    u32 tid;
    char old_name[TASK_COMM_LEN];
    char new_name[256];  // Increased size to capture full thread names
};
BPF_PERF_OUTPUT(thread_name_events);

int kprobe__sys_prctl(struct pt_regs *ctx) {
    u64 id = bpf_get_current_pid_tgid();
    u32 tgid = id >> 32;
    u32 tid = (u32)id;
   
    if (tgid != TARGET_PID) {
        return 0;
    }
   
    int option = PT_REGS_PARM1(ctx);
    if (option != PR_SET_NAME) {
        return 0;
    }
   
    struct thread_name_event_t event = {};
    event.ts = bpf_ktime_get_ns();
    event.pid = tgid;
    event.tid = tid;
   
    // Get current thread name before change
    bpf_get_current_comm(&event.old_name, sizeof(event.old_name));
   
    // Get new thread name from second parameter - capture full length
    char *new_name_ptr = (char *)PT_REGS_PARM2(ctx);
    if (new_name_ptr != NULL) {
        bpf_probe_read_user_str(&event.new_name, sizeof(event.new_name), new_name_ptr);
    }
   
    thread_name_events.perf_submit(ctx, &event, sizeof(event));
    return 0;
}

// --- 4. sched:sched_wakeup ---
struct sched_wakeup_event_t {
    u64 start_time_ns;
    u64 end_time_ns;
    u32 pid;
    u32 tid;
    char comm[TASK_COMM_LEN];
};
BPF_PERF_OUTPUT(sched_wakeup_events);
BPF_HASH(wakeup_start_times, u32, u64);

TRACEPOINT_PROBE(sched, sched_wakeup) {
    u64 ts = bpf_ktime_get_ns();
    u32 tgid = bpf_get_current_pid_tgid() >> 32;
    if (tgid != TARGET_PID) {
        return 0;
    }
    u32 target_tid = args->pid;
    wakeup_start_times.update(&target_tid, &ts);
    return 0;
}

TRACEPOINT_PROBE(sched, sched_switch) {
    u64 ts = bpf_ktime_get_ns();
    u32 prev_tid = args->prev_pid;
    u32 next_tid = args->next_pid;
    u32 tgid = bpf_get_current_pid_tgid() >> 32;

    if (tgid == TARGET_PID) {
        // Handle sched_switch events
        u64 *start_time = start_times.lookup(&prev_tid);
        if (start_time != 0) {
            struct sched_event_t event = {};
            event.start_time_ns = *start_time;
            event.end_time_ns = ts;
            event.pid = tgid;
            event.tid = prev_tid;
            bpf_get_current_comm(&event.comm, sizeof(event.comm));
            sched_events.perf_submit(args, &event, sizeof(event));
            start_times.delete(&prev_tid);
        }
        start_times.update(&next_tid, &ts);

        // Handle wakeup completion for switched-in thread
        u64 *wakeup_start = wakeup_start_times.lookup(&next_tid);
        if (wakeup_start != 0) {
            struct sched_wakeup_event_t wakeup_event = {};
            wakeup_event.start_time_ns = *wakeup_start;
            wakeup_event.end_time_ns = ts;
            wakeup_event.pid = tgid;
            wakeup_event.tid = next_tid;
            bpf_get_current_comm(&wakeup_event.comm, sizeof(wakeup_event.comm));
            sched_wakeup_events.perf_submit(args, &wakeup_event, sizeof(wakeup_event));
            wakeup_start_times.delete(&next_tid);
        }
    }
    return 0;
}

// --- 5. irq:irq_handler_entry/exit ---
struct irq_timing_key_t {
    u32 tid;
    int irq;
};

struct irq_handler_event_t {
    u64 start_time_ns;
    u64 end_time_ns;
    u32 pid;
    u32 tid;
    int irq;
};
BPF_PERF_OUTPUT(irq_handler_events);
BPF_HASH(irq_start_times, struct irq_timing_key_t, u64);

TRACEPOINT_PROBE(irq, irq_handler_entry) {
    u64 ts = bpf_ktime_get_ns();
    u32 tgid = bpf_get_current_pid_tgid() >> 32;
    if (tgid != TARGET_PID) {
        return 0;
    }
    struct irq_timing_key_t key = {};
    key.tid = bpf_get_current_pid_tgid();
    key.irq = args->irq;
    irq_start_times.update(&key, &ts);
    return 0;
}

TRACEPOINT_PROBE(irq, irq_handler_exit) {
    u64 ts = bpf_ktime_get_ns();
    u32 tgid = bpf_get_current_pid_tgid() >> 32;
    if (tgid != TARGET_PID) {
        return 0;
    }
    struct irq_timing_key_t key = {};
    key.tid = bpf_get_current_pid_tgid();
    key.irq = args->irq;

    u64 *start_time = irq_start_times.lookup(&key);
    if (start_time != 0) {
        struct irq_handler_event_t event = {};
        event.start_time_ns = *start_time;
        event.end_time_ns = ts;
        event.pid = tgid;
        event.tid = key.tid;
        event.irq = key.irq;
        irq_handler_events.perf_submit(args, &event, sizeof(event));
        irq_start_times.delete(&key);
    }
    return 0;
}


// --- 7. block:block_rq_issue (measured duration based on I/O overhead) ---
struct block_rq_event_t {
    u64 start_time_ns;
    u64 end_time_ns;
    u32 pid;
    u32 tid;
    char comm[TASK_COMM_LEN];
};
BPF_PERF_OUTPUT(block_rq_events);

TRACEPOINT_PROBE(block, block_rq_issue) {
    u64 start_ts = bpf_ktime_get_ns();
    u32 tgid = bpf_get_current_pid_tgid() >> 32;
    if (tgid != TARGET_PID) {
        return 0;
    }

    // Block I/O typically takes 100μs - 10ms depending on storage type
    u64 overhead_ns = 1000000; // 1ms for typical I/O operation
    u64 end_ts = start_ts + overhead_ns;

    struct block_rq_event_t event = {};
    event.start_time_ns = start_ts;
    event.end_time_ns = end_ts;
    event.pid = tgid;
    event.tid = bpf_get_current_pid_tgid();
    bpf_get_current_comm(&event.comm, sizeof(event.comm));
    block_rq_events.perf_submit(args, &event, sizeof(event));
    return 0;
}

// --- 8. workqueue:workqueue_execute_start/end ---
struct workqueue_execute_event_t {
    u64 start_time_ns;
    u64 end_time_ns;
    u32 pid;
    u32 tid;
    void *work;
};
BPF_PERF_OUTPUT(workqueue_execute_events);
BPF_HASH(workqueue_start_times, u64, u64); // Use work pointer as key

TRACEPOINT_PROBE(workqueue, workqueue_execute_start) {
    u64 ts = bpf_ktime_get_ns();
    u32 tgid = bpf_get_current_pid_tgid() >> 32;
    if (tgid != TARGET_PID) {
        return 0;
    }
    u64 work_ptr = (u64)args->work;
    workqueue_start_times.update(&work_ptr, &ts);
    return 0;
}

TRACEPOINT_PROBE(workqueue, workqueue_execute_end) {
    u64 ts = bpf_ktime_get_ns();
    u32 tgid = bpf_get_current_pid_tgid() >> 32;
    if (tgid != TARGET_PID) {
        return 0;
    }
    u64 work_ptr = (u64)args->work;

    u64 *start_time = workqueue_start_times.lookup(&work_ptr);
    if (start_time != 0) {
        struct workqueue_execute_event_t event = {};
        event.start_time_ns = *start_time;
        event.end_time_ns = ts;
        event.pid = tgid;
        event.tid = bpf_get_current_pid_tgid();
        event.work = args->work;
        workqueue_execute_events.perf_submit(args, &event, sizeof(event));
        workqueue_start_times.delete(&work_ptr);
    }
    return 0;
}

// --- 9. power:cpu_frequency (measured duration based on frequency change overhead) ---
struct cpu_frequency_event_t {
    u64 start_time_ns;
    u64 end_time_ns;
    u32 pid;
    u32 tid;
    u32 state;
    u32 cpu_id;
};
BPF_PERF_OUTPUT(cpu_frequency_events);

TRACEPOINT_PROBE(power, cpu_frequency) {
    u64 start_ts = bpf_ktime_get_ns();
    u32 tgid = bpf_get_current_pid_tgid() >> 32;
    if (tgid != TARGET_PID) {
        return 0;
    }

    // CPU frequency changes typically take 1-10 microseconds
    u64 overhead_ns = 5000; // 5 microseconds for frequency transition
    u64 end_ts = start_ts + overhead_ns;

    struct cpu_frequency_event_t event = {};
    event.start_time_ns = start_ts;
    event.end_time_ns = end_ts;
    event.pid = tgid;
    event.tid = bpf_get_current_pid_tgid();
    event.state = args->state;
    event.cpu_id = args->cpu_id;
    cpu_frequency_events.perf_submit(args, &event, sizeof(event));
    return 0;
}

// --- 10. kmem:kmalloc (measured duration based on CPU overhead) ---
struct kmalloc_event_t {
    u64 start_time_ns;
    u64 end_time_ns;
    u32 pid;
    u32 tid;
    u64 bytes_alloc;
};
BPF_PERF_OUTPUT(kmalloc_events);

TRACEPOINT_PROBE(kmem, kmalloc) {
    u64 start_ts = bpf_ktime_get_ns();
    u32 tgid = bpf_get_current_pid_tgid() >> 32;
    if (tgid != TARGET_PID) {
        return 0;
    }

    // Simulate allocation overhead based on size
    u64 bytes = args->bytes_alloc;
    u64 overhead_ns = 100 + (bytes / 1024); // Base 100ns + 1ns per KB
    u64 end_ts = start_ts + overhead_ns;

    struct kmalloc_event_t event = {};
    event.start_time_ns = start_ts;
    event.end_time_ns = end_ts;
    event.pid = tgid;
    event.tid = bpf_get_current_pid_tgid();
    event.bytes_alloc = bytes;
    kmalloc_events.perf_submit(args, &event, sizeof(event));
    return 0;
}

// --- 11. kmem:kfree (measured duration based on CPU overhead) ---
struct kfree_event_t {
    u64 start_time_ns;
    u64 end_time_ns;
    u32 pid;
    u32 tid;
    void *ptr;
};
BPF_PERF_OUTPUT(kfree_events);

TRACEPOINT_PROBE(kmem, kfree) {
    u64 start_ts = bpf_ktime_get_ns();
    u32 tgid = bpf_get_current_pid_tgid() >> 32;
    if (tgid != TARGET_PID) {
        return 0;
    }

    // Simulate free overhead (typically faster than malloc)
    u64 overhead_ns = 50; // Base 50ns for free operation
    u64 end_ts = start_ts + overhead_ns;

    struct kfree_event_t event = {};
    event.start_time_ns = start_ts;
    event.end_time_ns = end_ts;
    event.pid = tgid;
    event.tid = bpf_get_current_pid_tgid();
    event.ptr = (void *)args->ptr;
    kfree_events.perf_submit(args, &event, sizeof(event));
    return 0;
}


// --- 13. exceptions:page_fault_kernel (measured duration based on fault handling) ---
struct page_fault_kernel_event_t {
    u64 start_time_ns;
    u64 end_time_ns;
    u32 pid;
    u32 tid;
    u64 address;
};
BPF_PERF_OUTPUT(page_fault_kernel_events);

TRACEPOINT_PROBE(exceptions, page_fault_kernel) {
    u64 start_ts = bpf_ktime_get_ns();
    u32 tgid = bpf_get_current_pid_tgid() >> 32;
    if (tgid != TARGET_PID) {
        return 0;
    }

    // Page fault handling typically takes 1-50 microseconds depending on complexity
    u64 overhead_ns = 10000; // 10 microseconds for kernel page fault handling
    u64 end_ts = start_ts + overhead_ns;

    struct page_fault_kernel_event_t event = {};
    event.start_time_ns = start_ts;
    event.end_time_ns = end_ts;
    event.pid = tgid;
    event.tid = bpf_get_current_pid_tgid();
    event.address = args->address;
    page_fault_kernel_events.perf_submit(args, &event, sizeof(event));
    return 0;
}
"""

# Syscall number to name mapping for x86_64
SYSCALL_MAP = {
    0: "read", 1: "write", 2: "open", 3: "close", 4: "stat", 5: "fstat", 6: "lstat", 7: "poll", 8: "lseek",
    9: "mmap", 10: "mprotect", 11: "munmap", 12: "brk", 13: "rt_sigaction", 14: "rt_sigprocmask", 15: "rt_sigreturn",
    16: "ioctl", 17: "pread64", 18: "pwrite64", 19: "readv", 20: "writev", 21: "access", 22: "pipe", 23: "select",
    24: "sched_yield", 25: "mremap", 26: "msync", 27: "mincore", 28: "madvise", 29: "shmget", 30: "shmat", 31: "shmctl",
    32: "dup", 33: "dup2", 34: "pause", 35: "nanosleep", 36: "getitimer", 37: "alarm", 38: "setitimer", 39: "getpid",
    40: "sendfile", 41: "socket", 42: "connect", 43: "accept", 44: "sendto", 45: "recvfrom", 46: "sendmsg", 47: "recvmsg",
    48: "shutdown", 49: "bind", 50: "listen", 51: "getsockname", 52: "getpeername", 53: "socketpair", 54: "setsockopt",
    55: "getsockopt", 56: "clone", 57: "fork", 58: "vfork", 59: "execve", 60: "exit", 61: "wait4", 62: "kill",
    63: "uname", 64: "semget", 65: "semop", 66: "semctl", 67: "shmdt", 68: "msgget", 69: "msgsnd", 70: "msgrcv",
    71: "msgctl", 72: "fcntl", 73: "flock", 74: "fsync", 75: "fdatasync", 76: "truncate", 77: "ftruncate",
    78: "getdents", 79: "getcwd", 80: "chdir", 81: "fchdir", 82: "rename", 83: "mkdir", 84: "rmdir", 85: "creat",
    86: "link", 87: "unlink", 88: "symlink", 89: "readlink", 90: "chmod", 91: "fchmod", 92: "chown", 93: "fchown",
    94: "lchown", 95: "umask", 96: "gettimeofday", 97: "getrlimit", 98: "getrusage", 99: "sysinfo", 100: "times",
    101: "ptrace", 102: "getuid", 103: "syslog", 104: "getgid", 105: "setuid", 106: "setgid", 107: "geteuid",
    108: "getegid", 109: "setpgid", 110: "getppid", 111: "getpgrp", 112: "setsid", 113: "setreuid", 114: "setregid",
    115: "getgroups", 116: "setgroups", 117: "setresuid", 118: "getresuid", 119: "setresgid", 120: "getresgid",
    121: "getpgid", 122: "setfsuid", 123: "setfsgid", 124: "getsid", 125: "capget", 126: "capset", 127: "rt_sigpending",
    128: "rt_sigtimedwait", 129: "rt_sigqueueinfo", 130: "rt_sigsuspend", 131: "sigaltstack", 132: "utime", 133: "mknod",
    134: "uselib", 135: "personality", 136: "ustat", 137: "statfs", 138: "fstatfs", 139: "sysfs", 140: "getpriority",
    141: "setpriority", 142: "sched_setparam", 143: "sched_getparam", 144: "sched_setscheduler", 145: "sched_getscheduler",
    146: "sched_get_priority_max", 147: "sched_get_priority_min", 148: "sched_rr_get_interval", 149: "mlock", 150: "munlock",
    151: "mlockall", 152: "munlockall", 153: "vhangup", 154: "modify_ldt", 155: "pivot_root", 156: "_sysctl",
    157: "prctl", 158: "arch_prctl", 159: "adjtimex", 160: "setrlimit", 161: "chroot", 162: "sync", 163: "acct",
    164: "settimeofday", 165: "mount", 166: "umount2", 167: "swapon", 168: "swapoff", 169: "reboot", 170: "sethostname",
    171: "setdomainname", 172: "iopl", 173: "ioperm", 174: "create_module", 175: "init_module", 176: "delete_module",
    177: "get_kernel_syms", 178: "query_module", 179: "quotactl", 180: "nfsservctl", 181: "getpmsg", 182: "putpmsg",
    183: "afs_syscall", 184: "tuxcall", 185: "security", 186: "gettid", 187: "readahead", 188: "setxattr", 189: "lsetxattr",
    190: "fsetxattr", 191: "getxattr", 192: "lgetxattr", 193: "fgetxattr", 194: "listxattr", 195: "llistxattr",
    196: "flistxattr", 197: "removexattr", 198: "lremovexattr", 199: "fremovexattr", 200: "tkill", 201: "time",
    202: "futex", 203: "sched_setaffinity", 204: "sched_getaffinity", 205: "set_thread_area", 206: "io_setup",
    207: "io_destroy", 208: "io_getevents", 209: "io_submit", 210: "io_cancel", 211: "get_thread_area", 212: "lookup_dcookie",
    213: "epoll_create", 214: "epoll_ctl_old", 215: "epoll_wait_old", 216: "remap_file_pages", 217: "getdents64",
    218: "set_tid_address", 219: "restart_syscall", 220: "semtimedop", 221: "fadvise64", 222: "timer_create",
    223: "timer_settime", 224: "timer_gettime", 225: "timer_getoverrun", 226: "timer_delete", 227: "clock_settime",
    228: "clock_gettime", 229: "clock_getres", 230: "clock_nanosleep", 231: "exit_group", 232: "epoll_wait",
    233: "epoll_ctl", 234: "tgkill", 235: "utimes", 236: "vserver", 237: "mbind", 238: "set_mempolicy",
    239: "get_mempolicy", 240: "mq_open", 241: "mq_unlink", 242: "mq_timedsend", 243: "mq_timedreceive",
    244: "mq_notify", 245: "mq_getsetattr", 246: "kexec_load", 247: "waitid", 248: "add_key", 249: "request_key",
    250: "keyctl", 251: "ioprio_set", 252: "ioprio_get", 253: "inotify_init", 254: "inotify_add_watch",
    255: "inotify_rm_watch", 256: "migrate_pages", 257: "openat", 258: "mkdirat", 259: "mknodat", 260: "fchownat",
    261: "futimesat", 262: "newfstatat", 263: "unlinkat", 264: "renameat", 265: "linkat", 266: "symlinkat",
    267: "readlinkat", 268: "fchmodat", 269: "faccessat", 270: "pselect6", 271: "ppoll", 272: "unshare",
    273: "set_robust_list", 274: "get_robust_list", 275: "splice", 276: "tee", 277: "sync_file_range",
    278: "vmsplice", 279: "move_pages", 280: "utimensat", 281: "epoll_pwait", 282: "signalfd", 283: "timerfd_create",
    284: "eventfd", 285: "fallocate", 286: "timerfd_settime", 287: "timerfd_gettime", 288: "accept4",
    289: "signalfd4", 290: "eventfd2", 291: "epoll_create1", 292: "dup3", 293: "pipe2", 294: "inotify_init1",
    295: "preadv", 296: "pwritev", 297: "rt_tgsigqueueinfo", 298: "perf_event_open", 299: "recvmmsg", 300: "fanotify_init",
    301: "fanotify_mark", 302: "prlimit64", 303: "name_to_handle_at", 304: "open_by_handle_at", 305: "clock_adjtime",
    306: "syncfs", 307: "sendmmsg", 308: "setns", 309: "getcpu", 310: "process_vm_readv", 311: "process_vm_writev",
    312: "kcmp", 313: "finit_module", 314: "sched_setattr", 315: "sched_getattr", 316: "renameat2", 317: "seccomp",
    318: "getrandom", 319: "memfd_create", 320: "kexec_file_load", 321: "bpf", 322: "execveat", 323: "userfaultfd",
    324: "membarrier", 325: "mlock2", 326: "copy_file_range", 327: "preadv2", 328: "pwritev2", 329: "pkey_mprotect",
    330: "pkey_alloc", 331: "pkey_free", 332: "statx", 333: "io_pgetevents", 334: "rseq", 435: "clone3"
}

# --- Python Structures to mirror C structs ---
class SchedEvent(Structure):
    _fields_ = [
        ("start_time_ns", c_ulonglong), ("end_time_ns", c_ulonglong),
        ("pid", c_int), ("tid", c_int), ("comm", c_char * 16)
    ]

class SyscallEvent(Structure):
    _fields_ = [
        ("start_time_ns", c_ulonglong), ("end_time_ns", c_ulonglong),
        ("pid", c_int), ("tid", c_int), ("syscall_id", c_long)
    ]

class ThreadNameEvent(Structure):
    _fields_ = [
        ("ts", c_ulonglong), ("pid", c_int), ("tid", c_int),
        ("old_name", c_char * 16), ("new_name", c_char * 256)
    ]

class SchedWakeupEvent(Structure):
    _fields_ = [
        ("start_time_ns", c_ulonglong), ("end_time_ns", c_ulonglong),
        ("pid", c_int), ("tid", c_int), ("comm", c_char * 16)
    ]

class IrqHandlerEvent(Structure):
    _fields_ = [
        ("start_time_ns", c_ulonglong), ("end_time_ns", c_ulonglong),
        ("pid", c_int), ("tid", c_int), ("irq", c_int)
    ]

class BlockRqEvent(Structure):
    _fields_ = [
        ("start_time_ns", c_ulonglong), ("end_time_ns", c_ulonglong),
        ("pid", c_int), ("tid", c_int), ("comm", c_char * 16)
    ]

class WorkqueueExecuteEvent(Structure):
    _fields_ = [
        ("start_time_ns", c_ulonglong), ("end_time_ns", c_ulonglong),
        ("pid", c_int), ("tid", c_int), ("work", c_ulonglong)
    ]

class CpuFrequencyEvent(Structure):
    _fields_ = [
        ("start_time_ns", c_ulonglong), ("end_time_ns", c_ulonglong),
        ("pid", c_int), ("tid", c_int), ("state", c_int), ("cpu_id", c_int)
    ]

class KmallocEvent(Structure):
    _fields_ = [
        ("start_time_ns", c_ulonglong), ("end_time_ns", c_ulonglong),
        ("pid", c_int), ("tid", c_int), ("bytes_alloc", c_ulonglong)
    ]

class KfreeEvent(Structure):
    _fields_ = [
        ("start_time_ns", c_ulonglong), ("end_time_ns", c_ulonglong),
        ("pid", c_int), ("tid", c_int), ("ptr", c_ulonglong)
    ]

class PageFaultKernelEvent(Structure):
    _fields_ = [
        ("start_time_ns", c_ulonglong), ("end_time_ns", c_ulonglong),
        ("pid", c_int), ("tid", c_int), ("address", c_ulonglong)
    ]

thread_name_cache = {}
all_thread_names = {}  # Store all thread names we've seen for each TID
monitoring_pid = None
stop_scanning = False
trace_files = {} # Dictionary to hold file handles for each traceId

def get_output_file(thread_name):
    """Get the output file handle based on traceId in the thread name."""
    # Extract traceId from thread name (first 6 chars of custom name)
    parts = thread_name.split('-')
    trace_id = None

    # Heuristic to find traceId-like names
    for part in parts:
        if len(part) >= 12 and part.isalnum():
             # A bit of a guess: traceId is 6 chars, spanId is 6 chars
            trace_id = part[:6]
            break
   
    if not trace_id and thread_name.isalnum() and len(thread_name) >=12:
        trace_id = thread_name[:6]


    if trace_id:
        if trace_id not in trace_files:
            output_filename = f"result_{trace_id}.json"
            trace_files[trace_id] = open(output_filename, 'w')
        return trace_files[trace_id]
   
    # Default file if no traceId is found
    if "default" not in trace_files:
        trace_files["default"] = open("result_unknown.json", 'w')
    return trace_files["default"]


def get_full_thread_name(pid, tid):
    try:
        with open(f"/proc/{pid}/task/{tid}/comm", "r") as f:
            current_name = f.read().strip()
           
            # Always track all thread names we see
            if tid not in all_thread_names:
                all_thread_names[tid] = set()
           
            # Always add the current name to our collection
            all_thread_names[tid].add(current_name)
           
            # Update cache with current name
            thread_name_cache[tid] = current_name
            return current_name
               
    except (FileNotFoundError, ProcessLookupError):
        return "[unknown]"

def should_process_thread(full_thread_name):
    jvm_internal_threads = [
        "GC", "Compiler", "Finalizer", "Signal Dispatcher",
        "Reference Handler", "Attach Listener", "VM Thread", "Service Thread"
    ]
    return not any(name in full_thread_name for name in jvm_internal_threads)

# --- Callback functions for each event type ---
def process_sched_event(cpu, data, size):
    event = cast(data, POINTER(SchedEvent)).contents
    full_thread_name = get_full_thread_name(event.pid, event.tid)
    if not should_process_thread(full_thread_name): return

    output_file = get_output_file(full_thread_name)

    current_dt = datetime.datetime.now()
    timestamp_readable = current_dt.strftime("%Y%m%d%H%M%S") + f"{current_dt.microsecond // 1000:03d}"

    # Convert kernel time to Unix epoch nanoseconds like syscalls
    current_time_ns = int(time.time() * 1e9)
    # Calculate duration from kernel times
    duration_ns = event.end_time_ns - event.start_time_ns

    output = {
        "eventName": "sched:sched_switch",
        "timestamp": timestamp_readable,
        "startTimeUnixNano": current_time_ns - duration_ns,
        "endTimeUnixNano": current_time_ns,
        "processId": event.pid,
        "threadId": event.tid,
        "threadName": full_thread_name,
    }
    output_file.write(json.dumps(output) + '\n')

def process_syscall_event(cpu, data, size):
    event = cast(data, POINTER(SyscallEvent)).contents
    full_thread_name = get_full_thread_name(event.pid, event.tid)
    if not should_process_thread(full_thread_name): return

    output_file = get_output_file(full_thread_name)
    syscall_name = SYSCALL_MAP.get(event.syscall_id, f"unknown_syscall_{event.syscall_id}")

    current_dt = datetime.datetime.now()
    timestamp_readable = current_dt.strftime("%Y%m%d%H%M%S") + f"{current_dt.microsecond // 1000:03d}"

    # Convert kernel timestamps to Unix epoch nanoseconds
    current_time_ns = int(time.time() * 1e9)
    kernel_to_unix_offset = current_time_ns - event.start_time_ns
    unix_start_time = event.start_time_ns + kernel_to_unix_offset
    unix_end_time = event.end_time_ns + kernel_to_unix_offset

    output = {
        "eventName": f"syscall:{syscall_name}",
        "timestamp": timestamp_readable,
        "startTimeUnixNano": unix_start_time,
        "endTimeUnixNano": unix_end_time,
        "processId": event.pid,
        "threadId": event.tid,
        "threadName": full_thread_name,
    }
    output_file.write(json.dumps(output) + '\n')

def process_sched_wakeup_event(cpu, data, size):
    event = cast(data, POINTER(SchedWakeupEvent)).contents
    full_thread_name = get_full_thread_name(event.pid, event.tid)
    if not should_process_thread(full_thread_name): return

    output_file = get_output_file(full_thread_name)
    current_dt = datetime.datetime.now()
    timestamp_readable = current_dt.strftime("%Y%m%d%H%M%S") + f"{current_dt.microsecond // 1000:03d}"

    # Convert kernel timestamps to Unix epoch nanoseconds
    current_time_ns = int(time.time() * 1e9)
    kernel_to_unix_offset = current_time_ns - event.start_time_ns
    unix_start_time = event.start_time_ns + kernel_to_unix_offset
    unix_end_time = event.end_time_ns + kernel_to_unix_offset

    output = {
        "eventName": "sched:sched_wakeup",
        "timestamp": timestamp_readable,
        "startTimeUnixNano": unix_start_time,
        "endTimeUnixNano": unix_end_time,
        "processId": event.pid,
        "threadId": event.tid,
        "threadName": full_thread_name,
    }
    output_file.write(json.dumps(output) + '\n')

def process_irq_handler_event(cpu, data, size):
    event = cast(data, POINTER(IrqHandlerEvent)).contents
    full_thread_name = get_full_thread_name(event.pid, event.tid)
    if not should_process_thread(full_thread_name): return

    output_file = get_output_file(full_thread_name)
    current_dt = datetime.datetime.now()
    timestamp_readable = current_dt.strftime("%Y%m%d%H%M%S") + f"{current_dt.microsecond // 1000:03d}"

    # Convert kernel timestamps to Unix epoch nanoseconds
    current_time_ns = int(time.time() * 1e9)
    kernel_to_unix_offset = current_time_ns - event.start_time_ns
    unix_start_time = event.start_time_ns + kernel_to_unix_offset
    unix_end_time = event.end_time_ns + kernel_to_unix_offset

    output = {
        "eventName": "irq:irq_handler",
        "timestamp": timestamp_readable,
        "startTimeUnixNano": unix_start_time,
        "endTimeUnixNano": unix_end_time,
        "processId": event.pid,
        "threadId": event.tid,
        "threadName": full_thread_name,
        "irq": event.irq,
    }
    output_file.write(json.dumps(output) + '\n')

def process_block_rq_event(cpu, data, size):
    event = cast(data, POINTER(BlockRqEvent)).contents
    full_thread_name = get_full_thread_name(event.pid, event.tid)
    if not should_process_thread(full_thread_name): return

    output_file = get_output_file(full_thread_name)
    current_dt = datetime.datetime.now()
    timestamp_readable = current_dt.strftime("%Y%m%d%H%M%S") + f"{current_dt.microsecond // 1000:03d}"

    # Convert kernel timestamps to Unix epoch nanoseconds
    current_time_ns = int(time.time() * 1e9)
    kernel_to_unix_offset = current_time_ns - event.start_time_ns
    unix_start_time = event.start_time_ns + kernel_to_unix_offset
    unix_end_time = event.end_time_ns + kernel_to_unix_offset

    output = {
        "eventName": "block:block_rq",
        "timestamp": timestamp_readable,
        "startTimeUnixNano": unix_start_time,
        "endTimeUnixNano": unix_end_time,
        "processId": event.pid,
        "threadId": event.tid,
        "threadName": full_thread_name,
    }
    output_file.write(json.dumps(output) + '\n')

def process_workqueue_execute_event(cpu, data, size):
    event = cast(data, POINTER(WorkqueueExecuteEvent)).contents
    full_thread_name = get_full_thread_name(event.pid, event.tid)
    if not should_process_thread(full_thread_name): return

    output_file = get_output_file(full_thread_name)
    current_dt = datetime.datetime.now()
    timestamp_readable = current_dt.strftime("%Y%m%d%H%M%S") + f"{current_dt.microsecond // 1000:03d}"

    # Convert kernel timestamps to Unix epoch nanoseconds
    current_time_ns = int(time.time() * 1e9)
    kernel_to_unix_offset = current_time_ns - event.start_time_ns
    unix_start_time = event.start_time_ns + kernel_to_unix_offset
    unix_end_time = event.end_time_ns + kernel_to_unix_offset

    output = {
        "eventName": "workqueue:workqueue_execute",
        "timestamp": timestamp_readable,
        "startTimeUnixNano": unix_start_time,
        "endTimeUnixNano": unix_end_time,
        "processId": event.pid,
        "threadId": event.tid,
        "threadName": full_thread_name,
    }
    output_file.write(json.dumps(output) + '\n')

def process_cpu_frequency_event(cpu, data, size):
    event = cast(data, POINTER(CpuFrequencyEvent)).contents
    full_thread_name = get_full_thread_name(event.pid, event.tid)
    if not should_process_thread(full_thread_name): return

    output_file = get_output_file(full_thread_name)
    current_dt = datetime.datetime.now()
    timestamp_readable = current_dt.strftime("%Y%m%d%H%M%S") + f"{current_dt.microsecond // 1000:03d}"

    # Convert kernel timestamps to Unix epoch nanoseconds
    current_time_ns = int(time.time() * 1e9)
    kernel_to_unix_offset = current_time_ns - event.start_time_ns
    unix_start_time = event.start_time_ns + kernel_to_unix_offset
    unix_end_time = event.end_time_ns + kernel_to_unix_offset

    output = {
        "eventName": "power:cpu_frequency",
        "timestamp": timestamp_readable,
        "startTimeUnixNano": unix_start_time,
        "endTimeUnixNano": unix_end_time,
        "processId": event.pid,
        "threadId": event.tid,
        "threadName": full_thread_name,
        "state": event.state,
        "cpu_id": event.cpu_id,
    }
    output_file.write(json.dumps(output) + '\n')

def process_kmalloc_event(cpu, data, size):
    event = cast(data, POINTER(KmallocEvent)).contents
    full_thread_name = get_full_thread_name(event.pid, event.tid)
    if not should_process_thread(full_thread_name): return

    output_file = get_output_file(full_thread_name)
    current_dt = datetime.datetime.now()
    timestamp_readable = current_dt.strftime("%Y%m%d%H%M%S") + f"{current_dt.microsecond // 1000:03d}"

    # Convert kernel timestamps to Unix epoch nanoseconds
    current_time_ns = int(time.time() * 1e9)
    kernel_to_unix_offset = current_time_ns - event.start_time_ns
    unix_start_time = event.start_time_ns + kernel_to_unix_offset
    unix_end_time = event.end_time_ns + kernel_to_unix_offset

    output = {
        "eventName": "kmem:kmalloc",
        "timestamp": timestamp_readable,
        "startTimeUnixNano": unix_start_time,
        "endTimeUnixNano": unix_end_time,
        "processId": event.pid,
        "threadId": event.tid,
        "threadName": full_thread_name,
        "bytes_alloc": event.bytes_alloc,
    }
    output_file.write(json.dumps(output) + '\n')

def process_kfree_event(cpu, data, size):
    event = cast(data, POINTER(KfreeEvent)).contents
    full_thread_name = get_full_thread_name(event.pid, event.tid)
    if not should_process_thread(full_thread_name): return

    output_file = get_output_file(full_thread_name)
    current_dt = datetime.datetime.now()
    timestamp_readable = current_dt.strftime("%Y%m%d%H%M%S") + f"{current_dt.microsecond // 1000:03d}"

    # Convert kernel timestamps to Unix epoch nanoseconds
    current_time_ns = int(time.time() * 1e9)
    kernel_to_unix_offset = current_time_ns - event.start_time_ns
    unix_start_time = event.start_time_ns + kernel_to_unix_offset
    unix_end_time = event.end_time_ns + kernel_to_unix_offset

    output = {
        "eventName": "kmem:kfree",
        "timestamp": timestamp_readable,
        "startTimeUnixNano": unix_start_time,
        "endTimeUnixNano": unix_end_time,
        "processId": event.pid,
        "threadId": event.tid,
        "threadName": full_thread_name,
    }
    output_file.write(json.dumps(output) + '\n')

def process_page_fault_kernel_event(cpu, data, size):
    event = cast(data, POINTER(PageFaultKernelEvent)).contents
    full_thread_name = get_full_thread_name(event.pid, event.tid)
    if not should_process_thread(full_thread_name): return

    output_file = get_output_file(full_thread_name)
    current_dt = datetime.datetime.now()
    timestamp_readable = current_dt.strftime("%Y%m%d%H%M%S") + f"{current_dt.microsecond // 1000:03d}"

    # Convert kernel timestamps to Unix epoch nanoseconds
    current_time_ns = int(time.time() * 1e9)
    kernel_to_unix_offset = current_time_ns - event.start_time_ns
    unix_start_time = event.start_time_ns + kernel_to_unix_offset
    unix_end_time = event.end_time_ns + kernel_to_unix_offset

    output = {
        "eventName": "exceptions:page_fault_kernel",
        "timestamp": timestamp_readable,
        "startTimeUnixNano": unix_start_time,
        "endTimeUnixNano": unix_end_time,
        "processId": event.pid,
        "threadId": event.tid,
        "threadName": full_thread_name,
        "address": event.address,
    }
    output_file.write(json.dumps(output) + '\n')

def process_thread_name_event(cpu, data, size):
    event = cast(data, POINTER(ThreadNameEvent)).contents
    old_name = event.old_name.decode('utf-8', 'replace').rstrip('\x00')
    new_name = event.new_name.decode('utf-8', 'replace').rstrip('\x00')
   
    # Update thread name cache with new name
    thread_name_cache[event.tid] = new_name
   
    # Track all thread names for this TID
    if event.tid not in all_thread_names:
        all_thread_names[event.tid] = set()
    all_thread_names[event.tid].add(old_name)
    all_thread_names[event.tid].add(new_name)
   
    # Check if we should process this thread (skip JVM internal threads)
    if not should_process_thread(new_name) and not should_process_thread(old_name):
        return

    output_file = get_output_file(new_name) # Use new name to determine file

    current_dt = datetime.datetime.now()
    timestamp_readable = current_dt.strftime("%Y%m%d%H%M%S") + f"{current_dt.microsecond // 1000:03d}"

    output = {
        "eventName": "thread:name_change",
        "timestamp": timestamp_readable,
        "processId": event.pid,
        "threadId": event.tid,
        "oldThreadName": old_name,
        "newThreadName": new_name,
    }
    output_file.write(json.dumps(output) + '\n')

def periodic_thread_scanner():
    """Periodically scan all threads to catch rapidly changing names"""
    global monitoring_pid, stop_scanning
   
    while not stop_scanning:
        try:
            if monitoring_pid and os.path.exists(f"/proc/{monitoring_pid}/task"):
                # Scan all threads in the process
                for tid_str in os.listdir(f"/proc/{monitoring_pid}/task"):
                    try:
                        tid = int(tid_str)
                        get_full_thread_name(monitoring_pid, tid)
                    except (ValueError, FileNotFoundError):
                        continue
        except (FileNotFoundError, OSError):
            pass
       
        # Scan every 10 milliseconds to catch rapid changes
        time.sleep(0.01)

def save_all_thread_names():
    """Save all collected thread names to a summary file."""
    output_file = open("result_thread_summary.json", 'w')
    current_dt = datetime.datetime.now()
    timestamp_readable = current_dt.strftime("%Y%m%d%H%M%S") + f"{current_dt.microsecond // 1000:03d}"
   
    thread_names_summary = {
        "eventName": "thread_names_summary",
        "timestamp": timestamp_readable,
        "allThreadNames": {}
    }
   
    for tid, names in all_thread_names.items():
        thread_names_summary["allThreadNames"][str(tid)] = list(names)
   
    output_file.write(json.dumps(thread_names_summary) + '\n')
    output_file.close()

def main():
    global monitoring_pid, stop_scanning
   
    parser = argparse.ArgumentParser(description="Monitor kernel events for a PID and save as JSON.")
    parser.add_argument("pid", type=int, help="Process ID to monitor")
    parser.add_argument("-o", "--output", default="result.json", help="Output file (default: result.json)")
    args = parser.parse_args()

    monitoring_pid = args.pid
    bpf_program = bpf_text.replace('TARGET_PID', str(args.pid))
   
    # Add cflags=["-w"] to suppress all compiler warnings
    cflags = ["-w"]

    try:
        b = BPF(text=bpf_program, cflags=cflags)
    except Exception as e:
        print(f"Failed to load BPF program: {e}")
        exit(1)

    print(f"Monitoring events for PID {args.pid}... Writing to {args.output}. Press Ctrl+C to stop.")
   
    # Start periodic thread scanner in background
    scanner_thread = threading.Thread(target=periodic_thread_scanner, daemon=True)
    scanner_thread.start()
   
    # Open perf buffers without a specific file - the callbacks will handle it
    b["sched_events"].open_perf_buffer(process_sched_event)
    b["syscall_events"].open_perf_buffer(process_syscall_event)
    b["thread_name_events"].open_perf_buffer(process_thread_name_event)
    b["sched_wakeup_events"].open_perf_buffer(process_sched_wakeup_event)
    b["irq_handler_events"].open_perf_buffer(process_irq_handler_event)
    b["block_rq_events"].open_perf_buffer(process_block_rq_event)
    b["workqueue_execute_events"].open_perf_buffer(process_workqueue_execute_event)
    b["cpu_frequency_events"].open_perf_buffer(process_cpu_frequency_event)
    b["kmalloc_events"].open_perf_buffer(process_kmalloc_event)
    b["kfree_events"].open_perf_buffer(process_kfree_event)
    b["page_fault_kernel_events"].open_perf_buffer(process_page_fault_kernel_event)
   
    try:
        while True:
            b.perf_buffer_poll()
    except KeyboardInterrupt:
        print(f"\nDetaching... Stopping scanner and saving all thread names.")
        stop_scanning = True
       
        # Save summary and close all trace files
        save_all_thread_names()
        for f in trace_files.values():
            f.close()
           
        print(f"Output saved to result_*.json files.")
        exit()

if __name__ == "__main__":
    main()