/* 红包仅认同一凭证的金额和完成状态，不相信独立缓存金额或普通祝福。 */
function parse(raw, direction) {
  var root = common.object(common.json(raw));
  if (!root) return null;
  var m = common.object(root.mMap);
  var title, description, money, id = '', time = 0, observedAt = root.t, standardPayment = false;
  if (m) {
    if (['微信支付', '微信红包'].indexOf(common.text(m.source)) < 0) return null;
    title = common.text(m.title); description = common.text(m.description);
    var matched;
    if (direction === 'Expend' && /^已支付/.test(title)) {
      var evidence = common.text(m.source) === '微信红包' || common.text(m.display_name) === '微信红包';
      var parts = description.split(/\r?\n/), payment = null, completed = false;
      for (var lineIndex = 0; lineIndex < parts.length; lineIndex++) {
        var line = common.text(parts[lineIndex]);
        if (/^(?:交易类型[：:]\s*微信红包|用途[：:]\s*发红包)$/.test(line)) evidence = true;
        var paid = /^使用\s*(.{1,80}?)\s*支付\s*([¥￥]\s*[0-9][0-9,.]*)$/.exec(line);
        if (paid) { if (payment) return null; payment = paid; }
        if (/^交易状态[：:\s]*支付成功(?:[，,]对方已收款)?$/.test(line)) completed = true;
      }
      if (!evidence || !payment || !completed) return null;
      matched = /^已支付\s*([¥￥]\s*[0-9][0-9,.]*)$/.exec(title);
      if (!matched) return null;
      money = common.displayed(matched[1]);
      if (!money || common.displayed(payment[2]) !== money) return null;
      standardPayment = true;
    } else {
      var head = direction === 'Expend' ? '(?:红包发送成功|已发红包)' : '(?:红包领取成功|已领取红包)';
      matched = new RegExp('^' + head + '\\s*([¥￥]\\s*[0-9][0-9,.]*)$').exec(title);
      if (!matched) return null;
      money = common.displayed(matched[1]);
    }
    observedAt = m.t;
    if (Object.prototype.hasOwnProperty.call(m, 'transactionTime')) {
      time = common.transactionTime(m.transactionTime);
      if (time === null) return null;
    }
    if (Object.prototype.hasOwnProperty.call(m, 'currency') && ['CNY', 'RMB', '人民币'].indexOf(common.text(m.currency)) < 0) return null;
    id = common.text(m.orderId);
    if (id && !/^[A-Za-z0-9_-]{6,120}$/.test(id)) return null;
  } else {
    if (root.type !== 'redPackage' || (root.isSend !== 0 && root.isSend !== 1)) return null;
    var content = common.object(typeof root.content === 'string' ? common.json(root.content) : root.content);
    var msg = content && common.object(content.msg), app = msg && common.object(msg.appmsg);
    var pay = app && common.object(app.wcpayinfo);
    if (!pay || common.text(app.type) !== '2001') return null;
    title = common.text(app.title); description = common.text(app.des);
    // 发出的完成凭证与本人领取回执分别核验，不把对方领取通知当本人收入。
    if (direction === 'Expend' ? root.isSend !== 1 || title !== '红包发送成功' : root.isSend !== 0 || title !== '红包领取成功') return null;
    money = common.displayed(pay.feedesc);
    id = common.text(pay.sendid);
    if (!/^[A-Za-z0-9_-]{8,120}$/.test(id)) return null;
    if (Object.prototype.hasOwnProperty.call(pay, 'currency') && ['CNY', 'RMB', '人民币'].indexOf(common.text(pay.currency)) < 0) return null;
    if (Object.prototype.hasOwnProperty.call(pay, 'transactionTime')) {
      time = common.transactionTime(pay.transactionTime);
      if (time === null) return null;
    }
  }
  var combined = title + '\n' + description;
  if (!money || common.unsafe(combined) || /未领取|待领取|尚未领取|已退回|已退还|对方已领取|退款/.test(combined)) return null;
  var success = direction === 'Expend' ? /(?:^|\n)(?:红包已发出|红包发送成功)(?:$|\n)/ : /(?:^|\n)(?:本人已领取红包|红包领取成功)(?:$|\n)/;
  if (!standardPayment && !success.test(description)) return null;
  return common.result(direction, money, observedAt, '', '', time, id ? 'hongbao_' + id : '');
}
