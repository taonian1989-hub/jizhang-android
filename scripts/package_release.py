"""Package source only; never include local signing keys, build files or model secrets."""
from pathlib import Path
import hashlib
import shutil
import zipfile

root=Path(__file__).resolve().parents[1]
out=root/'outputs'
out.mkdir(exist_ok=True)
apk=out/'记账_0.1.0_预览版.apk'
if not apk.exists():
    raise SystemExit('Build the APK before packaging')
source=out/'记账_0.1.0_源码.zip'
allow_dirs=['app','scripts','tests','docs']
allow_files=['README.md','LICENSE','THIRD_PARTY_NOTICES.md','build.gradle','settings.gradle','CHANGELOG.md','.gitignore','.gitattributes']
files=[root/f for f in allow_files]
for folder in allow_dirs:
    files.extend(p for p in (root/folder).rglob('*') if p.is_file() and not any(x in {'build','.gradle','__pycache__'} for x in p.relative_to(root).parts))
with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(files):
        z.write(p,'jizhang-0.1.0/'+p.relative_to(root).as_posix())
shutil.copyfile(root/'docs/使用说明_v1.md',out/'记账_0.1.0_使用说明.md')
shutil.copyfile(root/'docs/验收记录_v1.md',out/'记账_0.1.0_验收记录.md')
(out/'记账_0.1.0_交付说明.md').write_text('''# 记账 0.1.0 个人预览版

安装 [Android APK](记账_0.1.0_预览版.apk)，支持 Android 8.0 及以上。将文件传到手机，按系统提示允许所选来源安装，打开即可使用，无需登录。

首版包含自然语言记账与确认卡片、收支/转账/退款、多账户和账本、预算、分类统计与趋势、周期账单、储蓄目标、JSON备份恢复和CSV导入导出。支持 DeepSeek、Kimi、GLM 和自定义兼容模型，联网搜索独立使用 GLM。

先到资产页设置期初余额，再试试“早餐18，地铁3”。配置AI请在设置→模型连接中填写自己的开放平台Key；需要联网时另配置GLM并主动开启搜索。

已通过160项本地自动检查与APK v2/v3签名校验。未连接安卓真机、未使用真实模型Key；真实手机加密存储、文件选择器、键盘安全区及实网调用仍需联调。浏览器界面截图使用明确标注的示例数据，不是真机截图。

本版不提供银行自动同步、截图OCR、云端多人协作或系统通知。CSV只支持本应用列格式。完整功能、隐私和容量边界见 [使用说明](记账_0.1.0_使用说明.md) 与 [验收记录](记账_0.1.0_验收记录.md)。

[源码压缩包](记账_0.1.0_源码.zip) 不含签名私钥。更新安装需使用原有签名；开发者请妥善保管本机work/signing中的签名文件。

卸载前请导出完整JSON备份。JSON/CSV备份为明文，请妥善保存。
''',encoding='utf-8')
with zipfile.ZipFile(apk) as z:
    for name in ['index.html','app.css','app.js','ledger.js']:
        if z.read('assets/'+name)!=(root/'app/src/main/assets'/name).read_bytes():
            raise SystemExit('APK assets differ from source: '+name)
with zipfile.ZipFile(source) as z:
    if any('/work/' in n or n.endswith(('.keystore','.enc','.apk','.jar','.class')) for n in z.namelist()):
        raise SystemExit('Unexpected private/build file in source archive')
manifest=out/'记账_0.1.0_SHA256.txt'
manifest.write_text('\n'.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name for p in sorted(out.iterdir()) if p.is_file() and p!=manifest)+'\n',encoding='utf-8')
print('Release packaged; APK assets match source; source contains no signing/build files.')
for p in sorted(out.iterdir()):
    if p.is_file():print(p.name,p.stat().st_size)
