#!/usr/bin/env python3
"""确定性生成麦米原创随包规则；--check校验源与产物一致，不读取旧资产实现。"""
from pathlib import Path
import argparse, hashlib, json
ROOT = Path(__file__).resolve().parent
OUT = ROOT.parent / 'src/main/assets/spec'
VERSION = 'maimi-rules-2'

def encoded(obj): return (json.dumps(obj,ensure_ascii=False,indent=2)+'\n').encode()

def products():
    rules=[]
    kinds=[('wechat-data','com.tencent.mm','DATA','微信凭证', [('expense','Expend','付款'),('income','Income','收款'),('refund','Refund','退款')]),
           ('alipay-data','com.eg.android.AlipayGphone','DATA','支付宝凭证',[('expense','Expend','付款'),('income','Income','收款'),('refund','Refund','退款')]),
           ('notice','com.tencent.mm','NOTICE','微信通知',[('expense','Expend','付款'),('income','Income','收款')]),
           ('notice','com.eg.android.AlipayGphone','NOTICE','支付宝通知',[('expense','Expend','付款'),('income','Income','收款')]),
           ('sms','com.android.phone','DATA','招行短信',[('expense','Expend','消费'),('income','Income','工资')]),
           ('wechat-transfer','com.tencent.mm','DATA','微信转账',[('expense','Expend','支出'),('income','Income','收入')]),
           ('wechat-redpackage','com.tencent.mm','DATA','微信红包',[('expense','Expend','支出'),('income','Income','收入')])]
    for source,app,kind,label,directions in kinds:
        for suffix,direction,name in directions:
            platform='wechat' if app=='com.tencent.mm' else 'alipay' if app=='com.eg.android.AlipayGphone' else 'cmb'
            channel=source.removeprefix('wechat-') if source in ('wechat-transfer','wechat-redpackage') else kind.lower()
            rid=f'maimi_{platform}_{channel}_{suffix}_v1'
            script=(ROOT/'src'/f'{source}.js').read_text()
            expression=f'var {rid}=(function(){{\n{script}\nreturn {{get:function(raw){{return parse(raw,{json.dumps(direction)},{json.dumps(app)});}}}};\n}})();'
            enabled=True
            evidence=['local-device-20261027-app-data-'+('39' if kind=='DATA' else '40')] if source in ('wechat-data','notice') and platform=='wechat' and suffix=='expense' else ['local-device-20261029-app-data-27-34'] if source=='wechat-transfer' and suffix=='expense' else []
            rules.append(dict(app=app,type=kind,systemRuleName=rid,name=f'麦米·{label}{name}',creator='maimi',autoRecord=0,enabled=1 if enabled else 0,struct='',js=expression,evidence=evidence))
    # 先识别有独立类型证据的红包付款，避免被通用付款规则提前短路。
    rules.sort(key=lambda row: 0 if '_redpackage_' in row['systemRuleName'] else 1)
    assert len(rules)==16 and len({x['systemRuleName'] for x in rules})==16
    assert sum(x['enabled'] for x in rules)==16
    categories=[dict(id=i+1,name=name,remoteId='',book=0,sort=i,type=kind) for i,(name,kind) in enumerate([('餐饮','Expend'),('交通','Expend'),('购物','Expend'),('医疗','Expend'),('房租','Expend'),('工资','Income'),('奖金','Income')])]
    assets=[dict(id=i+1,name=n,type='NORMAL',currency='CNY') for i,n in enumerate(['现金','微信钱包','支付宝','储蓄卡','信用卡'])]
    files={'rules_all.json':encoded(rules),'js_common.js':(ROOT/'src/common.js').read_bytes(),'js_category.js':(ROOT/'src/category.js').read_bytes(),'categories.json':encoded(categories),'assets.json':encoded(assets)}
    manifest=dict(schemaVersion=1,version=VERSION,files={k:hashlib.sha256(v).hexdigest() for k,v in files.items()},rules=[dict(id=x['systemRuleName'],app=x['app'],type=x['type'],enabled=bool(x['enabled']),evidence=x['evidence'],validationLevel='captured-structure' if x['evidence'] else 'synthetic-only') for x in rules],enabledCount=16)
    files['manifest.json']=encoded(manifest)
    return files

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--check',action='store_true'); args=parser.parse_args()
    files=products(); OUT.mkdir(parents=True,exist_ok=True)
    for name,data in files.items():
        if args.check:
            if not (OUT/name).exists() or (OUT/name).read_bytes()!=data: raise SystemExit(f'生成产物不一致：{name}')
        else: (OUT/name).write_bytes(data)
    print(f'{VERSION}: 16 rules, 16 enabled; '+('check passed' if args.check else 'generated'))
if __name__=='__main__': main()
