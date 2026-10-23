#!/usr/bin/env python3
"""CI：校验 shader 源码能编译，且仓库里的 .spv 与源码一一对应、两份副本一致。

为什么不比字节
--------------
不同 glslang 版本产出的 SPIR-V 字节可能不同（同一版本下 vulkan1.2 / vulkan1.4
两种 target-env 也不一致）。在 CI 里比字节会因工具链版本漂移而假报警，所以这里只查：

  · 每个随包 .spv 都能由对应源码编译出来   → 抓语法错误 / 宏失配 / 缺 include
  · 每个 .spv 都有对应源码，反之亦然       → 抓漏提交 / 改名后没同步
  · cpp/shaders 与 src/main/resources/shaders 两份 .spv 字节一致 → 抓只改了一份

编译口径与本地工具 tools/audit_spv_freshness.py（未随仓库发布）逐项一致：
生产档 + 地形 32B 顶点；先试 vulkan1.2，不通过再试 vulkan1.4。

用法：
    python3 ci/check_shaders.py [--glslang PATH]

退出码：0 全部通过；1 有失败。
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(ROOT, 'cpp', 'shaders')
SHIPPED_DIR = os.path.join(ROOT, 'src', 'main', 'resources', 'shaders')
DEV_DIR = SRC_DIR  # 开发机上 .spv 也留一份在 cpp/shaders

# 与 tools/audit_spv_freshness.py 的 BASE_FLAGS 逐项一致
BASE_FLAGS = ['-DRT_BUILD_PRODUCTION_MODE', '-DSECTION_VERTEX_32B=1']

STAGE = {
    '.rgen': 'rgen', '.rchit': 'rchit', '.rahit': 'rahit',
    '.rmiss': 'rmiss', '.comp': 'comp', '.vert': 'vert', '.frag': 'frag',
}

# 同一份源码的额外 permutation：产物名 -> (源文件名, 额外宏)
VARIANTS = {
    'rt_test_ser.rgen.spv': ('rt_test.rgen', ['-DRT_ENABLE_SER']),
    'rt_test_ser.rmiss.spv': ('rt_test.rmiss', ['-DRT_ENABLE_SER']),
    'rt_test_sharc_update.rgen.spv': ('rt_test.rgen', ['-DSHARC_UPDATE=1']),
    'rt_test_pomdeferred.rchit.spv': ('rt_test.rchit', ['-DPOM_DEFERRED_ONLY=1']),
}

TARGET_ENVS = ('vulkan1.2', 'vulkan1.4')


def find_glslang(explicit):
    if explicit:
        return explicit
    from_env = os.environ.get('GLSLANG')
    if from_env:
        return from_env
    for name in ('glslangValidator', 'glslang'):
        found = shutil.which(name)
        if found:
            return found
    sys.exit('找不到 glslangValidator：装 glslang-tools，或用 --glslang 指定路径')


def source_for(spv):
    if spv in VARIANTS:
        return VARIANTS[spv][0], list(VARIANTS[spv][1])
    return spv[:-len('.spv')], []


def supports_extension(glslang, name):
    """探测当前 glslang 是否认识某个 GLSL 扩展。"""
    probe_src = '#version 460\n#extension %s : require\nvoid main() {}\n' % name
    tmp_dir = tempfile.mkdtemp(prefix='glslang_probe_')
    src = os.path.join(tmp_dir, 'probe.comp')
    with open(src, 'w') as fh:
        fh.write(probe_src)
    for env in TARGET_ENVS:
        cmd = [glslang, '-V', '--target-env', env, '-S', 'comp', '-e', 'main',
               '-o', os.path.join(tmp_dir, 'probe.spv'), src]
        if subprocess.run(cmd, capture_output=True, text=True).returncode == 0:
            return True
    return False


def compile_one(glslang, source, extra, out, ser_supported=True):
    """按 target-env 依次尝试，返回 (status, info)，status ∈ {'ok','skip','fail'}。

    失败时把**每个** target-env 的报错都带上：老版本 glslang 可能不认识
    vulkan1.4，若只回最后一条，会把真正有用的 vulkan1.2 报错盖掉。

    工具链缺少 GL_EXT_shader_invocation_reorder（SER）时，用到它的 shader
    无法编译 —— 那不是源码的问题，标 skip 而不是 fail（本地用 Vulkan SDK
    的 glslang 1.4.x 仍然会真正编译它们）。
    """
    ext = os.path.splitext(source)[1]
    if ext not in STAGE:
        return 'fail', '无法从文件名判断 stage（扩展名 %r 不在 STAGE 表里）' % ext
    stage = STAGE[ext]
    errors = []
    for env in TARGET_ENVS:
        cmd = [glslang, '-V', '--target-env', env, '-S', stage, '-e', 'main']
        # -I：部分 shader 用了 #include 但没声明 GL_GOOGLE_include_directive，
        # 老版本 glslang 需要显式搜索路径才能解析。
        cmd += ['-I' + SRC_DIR]
        cmd += BASE_FLAGS + extra + ['-o', out, os.path.join(SRC_DIR, source)]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode == 0:
            return 'ok', env
        msg = (proc.stderr or proc.stdout).strip()
        if 'target-env' in msg and ('unknown' in msg.lower() or 'invalid' in msg.lower()):
            errors.append('[%s] 该 glslang 不支持这个 target-env' % env)
        else:
            errors.append('[%s]\n%s' % (env, msg))
    joined = '\n'.join(errors)
    if not ser_supported and 'GL_EXT_shader_invocation_reorder' in joined:
        return 'skip', '当前 glslang 不支持 GL_EXT_shader_invocation_reorder（SER）'
    return 'fail', joined


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--glslang', default=None)
    args = ap.parse_args()
    glslang = find_glslang(args.glslang)
    print('glslang: %s' % glslang)
    print('源目录 : %s' % SRC_DIR)
    print('随包目录: %s' % SHIPPED_DIR)

    if not os.path.isdir(SHIPPED_DIR):
        print('!! 找不到 %s' % SHIPPED_DIR)
        return 1
    shipped = sorted(f for f in os.listdir(SHIPPED_DIR) if f.endswith('.spv'))
    if not shipped:
        print('!! %s 下没有任何 .spv' % SHIPPED_DIR)
        return 1

    failures = []
    skipped = []
    tmp = tempfile.mkdtemp(prefix='spv_ci_')

    ser_ok = supports_extension(glslang, 'GL_EXT_shader_invocation_reorder')
    print('SER 扩展支持: %s' % ('是' if ser_ok else '否（SER 变体会标 SKIP）'))

    print('\n[1] 编译每个随包 shader')
    for spv in shipped:
        src, extra = source_for(spv)
        if not os.path.exists(os.path.join(SRC_DIR, src)):
            failures.append('%s：源码 %s 不存在' % (spv, src))
            print('  %-44s FAIL（源码 %s 不存在）' % (spv, src))
            continue
        status, info = compile_one(glslang, src, extra,
                                   os.path.join(tmp, spv), ser_ok)
        if status == 'ok':
            print('  %-44s OK %s' % (spv, info))
        elif status == 'skip':
            print('  %-44s SKIP（%s）' % (spv, info))
            skipped.append('%s：%s' % (spv, info))
        else:
            print('  %-44s FAIL' % spv)
            failures.append('%s（源 %s）:\n%s' % (spv, src, info))

    print('\n[2] 源码/产物一一对应')
    src_names = {f for f in os.listdir(SRC_DIR) if os.path.splitext(f)[1] in STAGE}
    covered = {source_for(spv)[0] for spv in shipped}
    for name in sorted(src_names - covered):
        print('  ! 源码 %-38s 没有对应 .spv（未被发行）' % name)

    print('\n[3] 与开发机本地副本（cpp/shaders/*.spv）是否一致')
    # 仓库里 cpp/shaders 只留源码，产物只有 src/main/resources/shaders 一份；
    # 本地若还留着旧副本，就顺便查一下有没有和随包那份跑偏（仅提示，不算失败）。
    checked = 0
    drift = []
    for spv in shipped:
        dev = os.path.join(DEV_DIR, spv)
        if not os.path.exists(dev):
            continue
        checked += 1
        with open(dev, 'rb') as a, open(os.path.join(SHIPPED_DIR, spv), 'rb') as b:
            if a.read() != b.read():
                drift.append(spv)
    if checked == 0:
        print('  （本地无副本，跳过）')
    else:
        print('  比对 %d 份，不一致 %d 份' % (checked, len(drift)))
        for spv in drift:
            print('  ! %-42s 本地副本与随包产物不同' % spv)

    print('\n================ 结果 ================')
    if skipped:
        print('SKIP（%d 项，工具链能力不足，非源码问题）' % len(skipped))
        for item in skipped:
            print('~ %s' % item)
    if failures:
        print('FAILED（%d 项）' % len(failures))
        for item in failures:
            print('- %s' % item)
        return 1
    print('PASS：%d 个 shader，编译通过 %d 个 / 跳过 %d 个'
          % (len(shipped), len(shipped) - len(skipped), len(skipped)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
