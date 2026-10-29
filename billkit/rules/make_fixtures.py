#!/usr/bin/env python3
"""独立合成回放 + 两条自家实机诊断的脱敏转写；不读取上游样本。"""
from pathlib import Path
import json
from generate import products
ROOT=Path(__file__).resolve().parent
T=1793289600000

def data_for(rule, amount='19.99'):
    direction=rule['systemRuleName'].split('_')[-2]
    app=rule['app']; kind=rule['type']
    if '_transfer_' in rule['systemRuleName']:
        transferid='SYNTH-TRANSFER-'+amount.replace(',','').replace('.','')
        pay=dict(paysubtype='1' if direction=='expense' else '3',feedesc='￥'+amount,transferid=transferid,paymsgid=transferid,begintransfertime=str(T//1000),pay_memo='合成转账')
        appmsg=dict(type='2000',title='微信转账',des='收到转账￥'+amount+'元。如需收钱，请点此升级至最新版本' if direction=='expense' else '本人已确认收款',wcpayinfo=pay)
        return dict(type='transfer',isSend=1,content=json.dumps(dict(msg=dict(appmsg=appmsg)),ensure_ascii=False),t=T)
    if '_redpackage_' in rule['systemRuleName']:
        return dict(mMap=dict(source='微信支付',title=('红包发送成功' if direction=='expense' else '已领取红包')+'¥'+amount,description='红包已发出' if direction=='expense' else '本人已领取红包',t=T,orderId='SYNTH-REDPACKET-'+amount.replace(',','').replace('.','')))
    if kind=='NOTICE':
        return dict(title='微信支付' if app=='com.tencent.mm' else '交易提醒',text=('已支付' if direction=='expense' else '已收款')+'¥'+amount,t=T,observedAt=T,envelopeVerified=True)
    if app=='com.tencent.mm':
        head={'expense':'已支付','income':'已收款','refund':'退款到账'}[direction]
        description={'expense':f'收款方：麦米测试店\n使用零钱支付¥{amount}\n交易状态支付成功，对方已收款','income':'收款已到账\n付款方：测试付款人','refund':'原支付退款已到账'}[direction]
        return dict(mMap=dict(title=head+'¥'+amount,description=description,source='微信支付',display_name='微信支付',t=T))
    if app=='com.eg.android.AlipayGphone':
        head={'expense':'付款成功','income':'收款成功','refund':'退款到账'}[direction]
        fields=[dict(title='付款方：' if direction=='income' else '收款方：',content='麦米测试店'),dict(title='付款方式：',content='余额')]
        pl=dict(title=head,money=amount,content=json.dumps(dict(money=amount,content=fields),ensure_ascii=False))
        return [dict(pl=json.dumps(pl,ensure_ascii=False),mct=T//1000)]
    verb='消费' if direction=='expense' else '工资入账'
    return dict(sender='95555',body=f'【招商银行】您尾号1234的借记卡{verb}人民币{amount}元，商户：麦米测试店。',t=T)

def clone(v): return json.loads(json.dumps(v))
def negatives(rule,raw):
    out=[]
    for case in range(5):
        v=clone(raw)
        if '_transfer_' in rule['systemRuleName']:
            content=json.loads(v['content']); pay=content['msg']['appmsg']['wcpayinfo']
            if case==0:
                v['isSend']=0; pay['paysubtype']='1'; content['msg']['appmsg']['des']='收到转账￥19.99元。如需收钱，请点此升级至最新版本'
            if case==1: pay['paysubtype']='4'
            if case==2: pay['feedesc']='￥19.999'
            if case==3: pay['begintransfertime']='not-a-time'
            if case==4: pay['paymsgid']='SYNTH-OTHER-TRANSFER'
            v['content']=json.dumps(content,ensure_ascii=False)
        elif '_redpackage_' in rule['systemRuleName']:
            m=v['mMap']
            if case==0: m['source']='普通公众号'
            if case==1: m['description']='红包未领取'
            if case==2: m['title']=m['title'].replace('19.99','19.999')
            if case==3: m['title']=m['title'].replace('19.99','0.00')
            if case==4:
                m['title']='红包发送成功'; m['cachedPayMoney']='19.99'
        elif rule['type']=='NOTICE':
            if case==0: v['envelopeVerified']=False
            if case==1: v['title']='普通聊天'
            if case==2: v['text']+=' 支付失败'
            if case==3: v['text']='[2条]微信支付: '+v['text']
            if case==4: v['text']=v['text'].replace('19.99','19.999')
        elif rule['app']=='com.tencent.mm':
            m=v['mMap']
            if case==0: m['source']='普通公众号'
            if case==1: m['description']='支付失败'
            if case==2: m['title']=m['title'].replace('19.99','19.999')
            if case==3: m['title']=m['title'].replace('19.99','0.00')
            if case==4: m['description']='余额100.00元，交易尚未完成'
        elif rule['app']=='com.eg.android.AlipayGphone':
            pl=json.loads(v[0]['pl']); content=json.loads(pl['content'])
            if case==0: pl['title']='交易处理中'
            if case==1: pl['money']='19.999'
            if case==2: content['money']='20.00'
            if case==3: content['content'].append(dict(title='状态：',content='支付失败'))
            if case==4: content['content'].append(dict(title='币种：',content='USD'))
            pl['content']=json.dumps(content,ensure_ascii=False); v[0]['pl']=json.dumps(pl,ensure_ascii=False)
        else:
            if case==0: v['sender']='10086'
            if case==1: v['body']=v['body'].replace('【招商银行】','【其他银行】')
            if case==2: v['body']=v['body'].replace('19.99','19.999')
            if case==3: v['body']=v['body'].replace('19.99','0.00')
            if case==4: v['body']+='请勿泄漏验证码123456'
        out.append(v)
    return out

rules=json.loads(products()['rules_all.json']); samples=[]
for r in rules:
    direction=r['systemRuleName'].split('_')[-2]
    types={'expense':'Expend','income':'Income','refund':'Refund'}
    for index,a in enumerate(['19.99','0.01','1,234.50']):
        raw=data_for(r,a); provenance='synthetic'; evidence='independent-contract-v1'
        merchant=''
        if r['app']=='com.tencent.mm' and r['type']=='DATA' and '_transfer_' not in r['systemRuleName'] and '_redpackage_' not in r['systemRuleName']: merchant='麦米测试店' if direction=='expense' else '测试付款人' if direction=='income' else ''
        elif r['type']=='DATA' and r['app']!='com.tencent.mm': merchant='麦米测试店'
        if r['app']=='com.tencent.mm' and direction=='expense' and index==1 and '_redpackage_' not in r['systemRuleName'] and '_transfer_' not in r['systemRuleName']:
            provenance='captured-redacted'; evidence='local-device-20261027-app-data-'+('39' if r['type']=='DATA' else '40')
            if r['type']=='DATA': raw['mMap']['description']='收款方测试商户甲\n使用零钱支付¥0.01\n交易状态支付成功，对方已收款'
            merchant=''
        if '_transfer_expense_' in r['systemRuleName'] and index==1:
            provenance='captured-redacted'; evidence='local-device-20261029-app-data-27'
        explicit='_transfer_' in r['systemRuleName']
        samples.append(dict(id=r['systemRuleName']+'-positive-'+str(index),app=r['app'],type=r['type'],provenance=provenance,evidence=evidence,raw=raw,expect=dict(match=True,ruleId=r['systemRuleName'],billType=types[direction],cents=round(float(a.replace(',',''))*100),merchant=merchant,hasExplicitTime=explicit,time=T if explicit else 0)))
    for index,raw in enumerate(negatives(r,data_for(r))):
        provenance='synthetic'; evidence='independent-negative-contract-v1'
        if '_transfer_' in r['systemRuleName'] and index==0:
            provenance='captured-redacted'; evidence='local-device-20261029-app-data-'+('29' if direction=='expense' else '33')
            content=json.loads(raw['content']); appmsg=content['msg']['appmsg']; pay=appmsg['wcpayinfo']
            pay['feedesc']='￥0.01'; pay['transferid']=pay['paymsgid']='SYNTH-TRANSFER-001' if direction=='expense' else 'SYNTH-TRANSFER-002'
            appmsg['des']='收到转账￥0.01元。如需收钱，请点此升级至最新版本'
            raw['content']=json.dumps(content,ensure_ascii=False)
        samples.append(dict(id=r['systemRuleName']+'-negative-'+str(index),app=r['app'],type=r['type'],provenance=provenance,evidence=evidence,raw=raw,expect=dict(match=False)))
out=ROOT.parent/'src/test/resources/spec/samples.json'; out.write_text(json.dumps(dict(schemaVersion=1,samples=samples),ensure_ascii=False,indent=2)+'\n')
print(f'{len(samples)}独立回放（48正/80负，含5条captured-redacted）')
