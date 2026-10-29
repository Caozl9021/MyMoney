/* 招行短信：号码、银行签名、卡片、完成方向、金额句式同时约束。 */
function parse(raw, direction) {
  var n = common.object(common.json(raw));
  if (!n || common.text(n.sender) !== '95555') return null;
  var body = common.text(n.body);
  if (common.unsafe(body) || /退款|转账|提现|还款|充值|验证码|美元|USD|港币/.test(body)) return null;
  if (body.indexOf('【招商银行】') === 0) body = body.substring(6);
  else if (body.lastIndexOf('【招商银行】') === body.length - 6) body = body.substring(0, body.length - 6);
  else return null;
  var date = '(?:(?:20[0-9]{2}年)?(?:0?[1-9]|1[0-2])月(?:0?[1-9]|[12][0-9]|3[01])日(?:\\s*(?:[01]?[0-9]|2[0-3]):[0-5][0-9])?)';
  var amount = '(?:0|[1-9][0-9]{0,6}|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?';
  var tail = '(?:[，,](?:交易后余额|余额|可用额度)(?:人民币)?' + amount + '元)?[。.]?';
  var verb = direction === 'Expend' ? '(?:消费|支出)' : '(?:工资入账|代发工资入账)';
  var templates = [
    { re: new RegExp('^您尾号([0-9]{4})的(借记卡|信用卡)(?:于?' + date + ')?' + verb + '人民币(' + amount + ')元(?:[，,]商户[：:]([^，,。\\n]{1,60}))?' + tail + '$'), card: 2, tail: 1, money: 3, shop: 4 },
    { re: new RegExp('^您(?:的)?(借记卡|信用卡)(?:尾号)?([0-9]{4})于?' + date + '(?:在([^，。\\n]{1,60}?))?' + verb + '人民币(' + amount + ')元' + tail + '$'), card: 1, tail: 2, money: 4, shop: 3 },
    { re: new RegExp('^您(?:账户|尾号)([0-9]{4})(?:的)?(借记卡|信用卡)于?' + date + '(?:在([^，。\\n]{1,60}?))?' + verb + '人民币(' + amount + ')元' + tail + '$'), card: 2, tail: 1, money: 4, shop: 3 }
  ];
  for (var i = 0; i < templates.length; i++) {
    var t = templates[i], m = t.re.exec(body);
    if (!m) continue;
    var money = common.amount(m[t.money]);
    if (!money) return null;
    return common.result(direction, money, n.t, m[t.shop], '招商银行' + m[t.card] + '(' + m[t.tail] + ')', 0, '');
  }
  return null;
}
