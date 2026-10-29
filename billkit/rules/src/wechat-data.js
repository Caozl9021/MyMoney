/* 输入来自本App mMap采集契约；成功付款结构由独立自家诊断39核对。 */
function parse(raw, direction) {
  var root = common.object(common.json(raw));
  var m = root && common.object(root.mMap);
  if (!m || common.text(m.source) !== '微信支付') return null;
  if (Object.prototype.hasOwnProperty.call(m, 'currency') && ['CNY', 'RMB', '人民币'].indexOf(common.text(m.currency)) < 0) return null;
  var title = common.text(m.title), description = common.text(m.description);
  if (!description || common.unsafe(title + '\n' + description)) return null;
  var heads = { Expend: '已支付', Income: '已收款', Refund: '退款到账' };
  var head = heads[direction];
  if (!head || title.indexOf(head) !== 0) return null;
  var money = common.displayed(title.substring(head.length));
  if (!money) return null;
  var method = '', merchant = '', parts = description.split(/\r?\n/);
  if (direction === 'Expend') {
    var payment = null, completed = false;
    for (var i = 0; i < parts.length; i++) {
      var line = common.text(parts[i]);
      var p = /^使用\s*(.{1,80}?)\s*支付\s*([¥￥]\s*[0-9][0-9,.]*)$/.exec(line);
      if (p) { if (payment) return null; payment = p; }
      if (/^交易状态[：:\s]*支付成功(?:[，,]对方已收款)?$/.test(line)) completed = true;
      var shop = /^收款方[：:]\s*(.{1,80})$/.exec(line);
      if (shop) merchant = shop[1];
    }
    if (!payment || !completed || common.displayed(payment[2]) !== money) return null;
    if (/退款|红包|转账|充值|提现|还款/.test(description)) return null;
    method = common.text(payment[1]);
  } else if (direction === 'Income') {
    if (!/^收款已到账(?:\n付款方[：:].+)?$/.test(description)) return null;
    var payer = /(?:^|\n)付款方[：:]\s*(.{1,80})$/.exec(description);
    merchant = payer ? payer[1] : '';
  } else if (description !== '原支付退款已到账') return null;
  var explicit = 0;
  if (Object.prototype.hasOwnProperty.call(m, 'transactionTime')) {
    explicit = common.transactionTime(m.transactionTime);
    if (explicit === null) return null;
  }
  return common.result(direction, money, m.t, merchant, method, explicit, m.orderId);
}
