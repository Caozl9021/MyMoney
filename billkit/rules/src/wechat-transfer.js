/* 本App独立现场转账凭证27—34核验方向；不把收款邀请当本人收入。 */
function parse(raw, direction) {
  var root = common.object(common.json(raw));
  if (!root || root.type !== 'transfer' || (root.isSend !== 0 && root.isSend !== 1)) return null;
  var content = common.object(typeof root.content === 'string' ? common.json(root.content) : root.content);
  var msg = content && common.object(content.msg), app = msg && common.object(msg.appmsg);
  var pay = app && common.object(app.wcpayinfo);
  if (!pay || common.text(app.type) !== '2000') return null;
  var title = common.text(app.title), description = common.text(app.des);
  if (title !== '微信转账' && title !== '已收款') return null;
  var text = title + '\n' + description;
  if (/失败|取消|撤销|过期|已退还|已退回|退还通知|转账退回|转账退款|未完成|待支付|未支付|支付中|处理中|未扣款|待扣款/.test(text)) return null;
  var subtype = common.text(pay.paysubtype), outcome = '';
  if (subtype === '1') {
    if (root.isSend !== 1) return null;
    outcome = 'Expend';
  } else if (subtype === '3') {
    var selfReceived = /(?:^|[。\n，,])(?:本人|你)已(?:确认)?收款(?:$|[。\n，,])/.test(text);
    var peerReceived = /(?:^|[。\n，,])(?:对方|收款方)已(?:确认)?收款(?:$|[。\n，,])/.test(text);
    if (selfReceived === peerReceived) return null;
    if (selfReceived && root.isSend === 1) outcome = 'Income';
    else if (peerReceived && root.isSend === 0) outcome = 'Expend';
    else return null;
  } else return null;
  if (outcome !== direction) return null;
  var money = common.displayed(pay.feedesc);
  var id = common.text(pay.transferid);
  if (!money || !/^[A-Za-z0-9_-]{8,117}$/.test(id)) return null;
  if (Object.prototype.hasOwnProperty.call(pay, 'paymsgid') && common.text(pay.paymsgid) !== id) return null;
  if (Object.prototype.hasOwnProperty.call(pay, 'currency') && ['CNY', 'RMB', '人民币'].indexOf(common.text(pay.currency)) < 0) return null;
  var statedAmounts = description.match(/[¥￥]\s*[0-9][0-9,.]*/g) || [];
  for (var amountIndex = 0; amountIndex < statedAmounts.length; amountIndex++)
    if (Number(common.displayed(statedAmounts[amountIndex])) !== Number(money)) return null;
  var time = common.transactionTime(pay.begintransfertime);
  if (time === null) return null;
  var result = common.result(direction, money, root.t, '', '', time, 'wxtransfer_' + id);
  result.shopItem = common.text(pay.pay_memo);
  return result;
}
