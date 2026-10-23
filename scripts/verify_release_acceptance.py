#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Story 10.15: 生产 External 验收门禁守门器 (release acceptance gate)。

在 ``release-acceptance`` profile 的 verify 阶段由 exec-maven-plugin 调用:
解析 surefire 报告目录, 断言必需测试集满足:

1. 每个必需测试类都产出了<b>本次运行</b>的 surefire 报告 (报告缺失 = 测试压根没跑,
   属静默缺口; 陈旧报告由 pom 的 initialize 阶段 clean execution 预先清除,
   故 -DskipTests / -Dtest 覆盖等跳过场景也会以「报告缺失」在此暴露);
2. 每份报告 skipped == 0、failures == 0、errors == 0 (surefire 无法原生把
   skipped 计为失败, 故由本脚本独立裁决, 防 assume-skip 洗绿);
3. 报告内 testcase 数与 testsuite tests 计数一致 (防报告残缺)。

任一缺口即打印明细并以非零码退出, 构建失败。

另外检查 ``target/release-acceptance/pending-cleanup.txt`` (release 测试
draft/delete 清理失败时写入的待人工清理清单; preflight 阶段每次运行前清零):
存在非空即输出 CONCERN 提示 (mediaId 仅前缀脱敏), 但不改变退出码 —
清理失败不掩盖测试结果, 由人工负责后续清理; 文件不可读仅告警跳过, 不影响裁决。

用法: python3 scripts/verify_release_acceptance.py <surefire-reports 目录> <必需测试类全限定名>...
      (必需类清单由 pom.xml release-acceptance profile 的 gate execution 显式传入,
       pom 为单一事实源; 本脚本不内嵌清单, 避免双份清单漂移)

只用 Python3 标准库; 兼容 Python 3.9+ (``from __future__ import annotations``)。
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# 待人工清理清单 (release 测试清理失败时由测试写入; full mediaId 只落 target/, 不入仓库)
PENDING_CLEANUP_FILE = "target/release-acceptance/pending-cleanup.txt"

# CONCERN 输出脱敏: draftMediaId 只保留前 8 位
_MEDIA_ID_PATTERN = re.compile(r"(draftMediaId=)(\S{8})\S*")


def find_report(reports_dir: Path, class_name: str) -> Path | None:
    """定位某测试类的 surefire XML 报告; 精确名优先, 兼容带后缀的变体 (如参数化/重跑)."""
    exact = reports_dir / f"TEST-{class_name}.xml"
    if exact.exists():
        return exact
    candidates = sorted(reports_dir.glob(f"TEST-{class_name}*.xml"))
    return candidates[0] if candidates else None


def parse_report(report_path: Path) -> dict:
    """解析单个 surefire XML 报告, 汇总计数与失败/跳过明细.

    报告不可解析 (截断/损坏) 时抛 ValueError, 由调用方计入缺口明细而非裸崩溃。
    """
    try:
        root = ET.parse(report_path).getroot()
    except ET.ParseError as e:
        raise ValueError(f"XML 无法解析 ({e})") from e
    summary = {
        "path": report_path,
        "tests": int(root.get("tests", "0")),
        "skipped": int(root.get("skipped", "0")),
        "failures": int(root.get("failures", "0")),
        "errors": int(root.get("errors", "0")),
        "cases": [],  # (name, outcome) — outcome ∈ {passed, skipped, failure, error}
    }
    for case in root.iter("testcase"):
        name = case.get("name", "<unknown>")
        if case.find("skipped") is not None:
            summary["cases"].append((name, "skipped"))
        elif case.find("failure") is not None:
            summary["cases"].append((name, "failure"))
        elif case.find("error") is not None:
            summary["cases"].append((name, "error"))
        else:
            summary["cases"].append((name, "passed"))
    return summary


def mask_media_ids(line: str) -> str:
    """CONCERN 输出脱敏: draftMediaId= 只保留前 8 位前缀。"""
    return _MEDIA_ID_PATTERN.sub(r"\1\2***", line)


def report_pending_cleanup(project_root: Path) -> None:
    """输出待人工清理 CONCERN (不改退出码); 文件不可读只告警, 不影响裁决。"""
    pending_cleanup = project_root / PENDING_CLEANUP_FILE
    if not pending_cleanup.is_file():
        return
    try:
        content = pending_cleanup.read_text(encoding="utf-8").strip()
    except OSError as e:
        print(f"[WARN] 待清理清单不可读, CONCERN 检查跳过: {e.__class__.__name__}")
        return
    if not content:
        return
    print("[CONCERN] 存在待人工清理的真实微信草稿 (draft/delete 自动清理失败):")
    for line in content.splitlines():
        print(f"  - {mask_media_ids(line)}")
    print("  请到微信公众号后台手工删除上述草稿。")


def main() -> int:
    args = sys.argv[1:]
    if len(args) < 2:
        print("[GATE-FAIL] 用法: verify_release_acceptance.py <surefire-reports 目录> "
              "<必需测试类全限定名>... (必需类清单由 pom gate execution 传入)")
        return 1
    reports_dir = Path(args[0])
    required_tests = args[1:]

    project_root = Path(__file__).resolve().parent.parent

    print("== Story 10.15 release acceptance 守门器 ==")
    print(f"报告目录: {reports_dir}")
    print(f"必需测试集 ({len(required_tests)} 项, 来源: pom release-acceptance profile):")
    for class_name in required_tests:
        print(f"  - {class_name}")

    if not reports_dir.is_dir():
        print(f"[GATE-FAIL] surefire 报告目录不存在: {reports_dir} — 必需套件根本未执行")
        return 1

    gaps: list[str] = []

    for class_name in required_tests:
        report_path = find_report(reports_dir, class_name)
        if report_path is None:
            gaps.append(f"报告缺失: {class_name} — 测试类未执行 (静默缺口)")
            continue
        try:
            summary = parse_report(report_path)
        except ValueError as e:
            gaps.append(f"报告不可解析: {class_name} — {e}")
            continue
        print(f"- {class_name}")
        print(f"  报告: {report_path.name} (tests={summary['tests']}, "
              f"failures={summary['failures']}, errors={summary['errors']}, "
              f"skipped={summary['skipped']})")

        if summary["tests"] == 0:
            gaps.append(f"空报告: {class_name} — 报告内无任何 testcase")
        # 报告残缺防护: testcase 数须与 testsuite 计数一致
        if len(summary["cases"]) != summary["tests"]:
            gaps.append(f"报告残缺: {class_name} — testcase 数 {len(summary['cases'])} "
                        f"≠ testsuite tests={summary['tests']}")
        if summary["skipped"] > 0:
            skipped_names = [n for n, o in summary["cases"] if o == "skipped"]
            gaps.append(f"出现 skipped: {class_name} — 跳过方法 {skipped_names} "
                        f"(release 必需测试不得以 skip 计为通过)")
        if summary["failures"] > 0:
            failed_names = [n for n, o in summary["cases"] if o == "failure"]
            gaps.append(f"出现 failures: {class_name} — 失败方法 {failed_names}")
        if summary["errors"] > 0:
            error_names = [n for n, o in summary["cases"] if o == "error"]
            gaps.append(f"出现 errors: {class_name} — 出错方法 {error_names}")

    # CONCERN: 清理失败待人工处理 (不掩盖测试结果, 不改变退出码; 不可读只告警)
    report_pending_cleanup(project_root)

    if gaps:
        print(f"[GATE-FAIL] release acceptance 未通过, 共 {len(gaps)} 项缺口:")
        for gap in gaps:
            print(f"  - {gap}")
        return 1

    print("[GATE-PASS] 必需测试集全部真实执行: 0 skipped / 0 failures / 0 errors, 报告齐全。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
