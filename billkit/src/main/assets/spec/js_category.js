/* 原创兼容兜底。用户清单/学习/餐段由Kotlin主推荐负责，不另建固定时段。 */
var category = {
  get: function (shop, type, repeatedShop, item, hhmm, extra) {
    if (type !== 'Expend' && type !== 'Income') return null;
    var text = [common.text(shop), common.text(item), common.text(extra)].join(' ');
    var rules = type === 'Income' ? [['工资', '工资'], ['奖金', '奖金']] :
      [['公交地铁', '公交地铁'], ['挂号', '医疗'], ['房租', '房租']];
    for (var i = 0; i < rules.length; i++) if (text.indexOf(rules[i][0]) >= 0)
      return { category: rules[i][1], book: '' };
    return null;
  }
};
