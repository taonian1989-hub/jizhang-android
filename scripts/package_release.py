"""Create versioned public artifacts while excluding local secrets and build data."""
from pathlib import Path
import hashlib,shutil,xml.etree.ElementTree as ET,zipfile

root=Path(__file__).resolve().parents[1]
version=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot().attrib['{http://schemas.android.com/apk/res/android}versionName']
out=root/'outputs';out.mkdir(exist_ok=True)
prefix='记账_'+version+'_'
apk=out/(prefix+'预览版.apk')
if not apk.exists():raise SystemExit('Build the APK before packaging')
source=out/(prefix+'源码.zip')
files=[root/f for f in ['README.md','LICENSE','THIRD_PARTY_NOTICES.md','CHANGELOG.md','build.gradle','settings.gradle','.gitignore','.gitattributes']]
for folder in ['app','scripts','tests','docs']:
    files.extend(p for p in (root/folder).rglob('*') if p.is_file() and not any(x in {'build','.gradle','__pycache__'} for x in p.relative_to(root).parts))
with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(files):z.write(p,'jizhang-'+version+'/'+p.relative_to(root).as_posix())
for src,suffix in [('使用说明_v1.md','使用说明.md'),('验收记录_v1.md','验收记录.md')]:shutil.copyfile(root/'docs'/src,out/(prefix+suffix))
(out/(prefix+'交付说明.md')).write_text(f'''# 记账 {version} 个人预览版

支持Android 8.0及以上。此版联网搜索切换为DeepSeek官方Search API，复用DeepSeek官方Key，不再需要GLM搜索Key；旧搜索设置自动迁移，语音和动态记账表单保留。原版可使用相同签名覆盖安装，无需卸载，建议升级前先导出JSON备份。

语音优先使用手机可用的本地识别服务，兼容系统语音窗口；首次主动使用时申请麦克风权限。识别结果先回填文字，不会自动发问或记账。本应用不保存原始音频，系统语音服务可能联网；没有可用服务时可使用输入法的语音键。

四种表单只显示匹配的账户和分类。转账不保存消费分类，日期、账本和可选备注/标签保留。256项本地检查通过，APK v2/v3签名有效；真实麦克风、各机型识别服务和实际Key调用仍待真机联调。截图使用标注的内置演示数据。

[安装APK]({prefix}预览版.apk) · [完整源码]({prefix}源码.zip) · [使用说明]({prefix}使用说明.md) · [验收记录]({prefix}验收记录.md)

源码包不含签名私钥、模型Key或用户账本。导出的JSON/CSV备份为明文，请妥善保管。
''',encoding='utf-8')
with zipfile.ZipFile(apk) as z:
    for name in ['index.html','app.css','app.js','ledger.js','speech.js']:
        if z.read('assets/'+name)!=(root/'app/src/main/assets'/name).read_bytes():raise SystemExit('APK asset differs: '+name)
with zipfile.ZipFile(source) as z:
    if any('/work/' in n or n.endswith(('.keystore','.enc','.apk','.jar','.class','README.local.md')) for n in z.namelist()):raise SystemExit('Private/build file in source')
manifest=out/(prefix+'SHA256.txt')
manifest.write_text('\n'.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name for p in sorted(out.iterdir()) if p.is_file() and p!=manifest and p.name.startswith(prefix))+'\n',encoding='utf-8')
print('Packaged version '+version+'; APK assets match source; signing/build files excluded.')
