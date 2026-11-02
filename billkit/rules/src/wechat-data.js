/* 输入来自本App mMap采集契约；成功付款结构由独立自家诊断39核对。 */
function parse(raw, direction, app, purpose) {
  var root = common.object(common.json(raw));
  var m = root && common.object(root.mMap);
  if (!m || common.text(m.source) !== '微信支付') return null;
  if (Object.prototype.hasOwnProperty.call(m, 'currency') && ['CNY', 'RMB', '人民币'].indexOf(common.text(m.currency)) < 0) return null;
  var display = common.text(m.display_name);
  var repayment = purpose === 'repayment';
  if (repayment && (direction !== 'Expend' || display !== '美团金融还款')) return null;
  if (!repayment && direction === 'Expend' && (/退款|转账|充值|提现|还款/.test(display) || /^(?:微信)?红包$/.test(display))) return null;
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
    if (common.unsafe(display)) return null;
    for (var i = 0; i < parts.length; i++) {
      var line = common.text(parts[i]);
      var p = /^使用\s*(.{1,80}?)\s*支付\s*([¥￥]\s*[0-9][0-9,.]*)$/.exec(line);
      if (p) { if (payment) return null; payment = p; }
      if (/^交易状态/.test(line)) {
        if (!/^交易状态[：:\s]*支付成功(?:[，,]对方已收款)?$/.test(line)) return null;
        completed = true;
      }
      var shop = /^收款方[：:]\s*(.{1,80})$/.exec(line);
      if (shop) { if (merchant && merchant !== shop[1]) return null; merchant = shop[1]; }
      if (!p && !shop && !/^交易状态/.test(line) && !/^收款方[^：:¥￥0-9]{1,80}$/.test(line)) return null;
    }
    // 精简凭证只允许完整单行付款；额外正文不能借成功标题绕过原完成状态门槛。
    if (!payment || (!completed && parts.length !== 1) || Number(common.displayed(payment[2])) !== Number(money)) return null;
    if (merchant && display && display !== '微信支付' && merchant !== display) return null;
    if (!merchant && display !== '微信支付') merchant = display;
    if (repayment && merchant !== '美团金融还款') return null;
    var flowText = repayment ? description.replace(/^收款方[：:]\s*美团金融还款$/m, '') : description;
    if (/退款|红包|转账|充值|提现|还款/.test(flowText)) return null;
    method = common.text(payment[1]);
    if (/[¥￥]|USD|HKD|JPY|EUR|GBP|AUD|CAD|SGD|美元|港币|港元|日元|欧元|英镑|澳元|加元|新加坡元|手续费|支付状态|交易状态/.test(method)) return null;
    if (merchant.length > 80) return null;
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
