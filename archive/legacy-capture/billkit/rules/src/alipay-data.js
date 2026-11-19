/* 结构化凭证按业务字段独立解析；当前按用户要求启用，尚无独立真实交易回放证据。 */
function parse(raw, direction) {
  var rows = common.json(raw);
  if (!Array.isArray(rows) || rows.length !== 1) return null;
  var row = common.object(rows[0]);
  var pl = row && common.object(typeof row.pl === 'string' ? common.json(row.pl) : row.pl);
  if (!pl) return null;
  var content = common.object(typeof pl.content === 'string' ? common.json(pl.content) : pl.content);
  if (!content) return null;
  var fields = common.fields(content.content);
  if (!fields) return null;
  var title = common.text(pl.title), inferred = '';
  if (common.unsafe(title)) return null;
  if (title === '付款成功' || title === '支付成功') inferred = 'Expend';
  else if (title === '收款成功') inferred = 'Income';
  else if (title === '退款到账' || title === '退款成功') inferred = 'Refund';
  else if (title !== '交易成功') return null;
  var directionFields = [fields['收支类型'], fields['交易类型'], fields['方向']];
  for (var d = 0; d < directionFields.length; d++) {
    var label = common.text(directionFields[d]);
    if (!label) continue;
    var resolved = /^(?:支出|消费|付款)$/.test(label) ? 'Expend' : /^(?:收入|收款)$/.test(label) ? 'Income' : label === '退款' ? 'Refund' : '';
    if (!resolved || (inferred && inferred !== resolved)) return null;
    inferred = resolved;
  }
  if (inferred !== direction) return null;
  var statusKeys = ['交易状态', '支付状态', '付款状态', '收款状态', '退款状态', '订单状态'];
  var acceptedStatus = { Expend: ['支付成功', '付款成功', '已支付'], Income: ['收款成功', '已收款'], Refund: ['退款成功', '退款到账'] };
  for (var statusIndex = 0; statusIndex < statusKeys.length; statusIndex++) {
    if (!Object.prototype.hasOwnProperty.call(fields, statusKeys[statusIndex])) continue;
    var status = common.text(fields[statusKeys[statusIndex]]);
    if (['成功', '交易成功', '已完成'].indexOf(status) < 0 && acceptedStatus[direction].indexOf(status) < 0) return null;
  }
  function signedAmount(value) {
    var text = common.moneyText(value);
    if (direction === 'Expend' && text.charAt(0) === '-') text = text.substring(1);
    return common.amount(text);
  }
  var hasTop = Object.prototype.hasOwnProperty.call(pl, 'money');
  var hasNested = Object.prototype.hasOwnProperty.call(content, 'money');
  if (!hasTop && !hasNested) return null;
  var top = hasTop ? signedAmount(pl.money) : null;
  var nested = hasNested ? signedAmount(content.money) : null;
  if ((hasTop && !top) || (hasNested && !nested)) return null;
  if (hasTop && hasNested && Number(top) !== Number(nested)) return null;
  var money = top || nested;
  var currencySources = [pl, content, fields], currencyKeys = ['currency', 'currency', '币种'];
  for (var c = 0; c < currencySources.length; c++)
    if (Object.prototype.hasOwnProperty.call(currencySources[c], currencyKeys[c]) &&
        ['CNY', 'RMB', '人民币'].indexOf(common.text(currencySources[c][currencyKeys[c]])) < 0) return null;
  var joined = '';
  for (var key in fields) if (Object.prototype.hasOwnProperty.call(fields, key)) joined += key + ':' + fields[key] + '\n';
  if (common.unsafe(joined) || /转账|提现|充值|还款|红包|手续费|USD|美元|港币/.test(joined)) return null;
  if (direction !== 'Refund' && /退款/.test(joined)) return null;
  var merchant = direction === 'Income' ? fields['付款方'] : fields['收款方'];
  var explicit = 0;
  if (Object.prototype.hasOwnProperty.call(fields, '交易时间戳')) {
    explicit = common.transactionTime(fields['交易时间戳']);
    if (explicit === null) return null;
  }
  return common.result(direction, money, row.mct, merchant || fields['商户名称'],
    fields['付款方式'], explicit, fields['订单号']);
}
