/* 通知只接受宿主信封验证后的完整交易句，不以聊天文字或汇总代替交易。 */
function parse(raw, direction, app) {
  var n = common.object(common.json(raw));
  if (!n || n.envelopeVerified !== true) return null;
  var title = common.text(n.title), body = common.text(n.text);
  if (app === 'com.tencent.mm' ? title !== '微信支付' : title !== '交易提醒') return null;
  if (common.unsafe(body) || /退款|转账|红包|优惠|奖励|营销|\[[0-9]+条\]/.test(body)) return null;
  var head = direction === 'Expend' ? '(?:已支付|支付成功|付款成功)' : '(?:已收款|收款成功)';
  var match = new RegExp('^' + head + '[：:\\s]*([¥￥][0-9][0-9,.]*)$').exec(body);
  var money = match ? common.displayed(match[1]) : null;
  if (!money && app === 'com.eg.android.AlipayGphone') {
    var noun = direction === 'Expend' ? '支出' : '收入';
    var sentence = new RegExp('^你有一笔\\s*([0-9][0-9,.]*)\\s*元的' + noun + '[。！]?$').exec(body);
    if (sentence) money = common.amount(sentence[1]);
  }
  return money ? common.result(direction, money, n.observedAt || n.t, '', '', 0, '') : null;
}
