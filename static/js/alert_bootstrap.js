/**
 * 显示提示消息（自动关闭）
 * @param msg
 * @param sec 显示时间（毫秒）
 * @param callback 回调函数
 */
function showTip(msg, sec, callback){if(!sec) {sec = 1000;}Modal.tip({title:'提示', msg: msg}, sec);setTimeout(callback, sec);}
/**
 * 显示消息
 * @param msg
 */
function showMsg(msg, callback){Modal.alert({title:'提示', msg: msg, btnok: '确定'}).on(function (e) {if(callback){callback();}});}
/**
 * 模态对话框
 * @param msg
 * @returns
 */
function showConfirm(msg,callback){//var res = false;
    Modal.confirm(
      {
          title:'提示',
          msg: msg,
      }).on( function (e) {callback();});

}
/***
 * 模态框封装
 */
$(function () {
  window.Modal = function () {
    var reg = new RegExp("\\[([^\\[\\]]*?)\\]", 'igm');
    var alr = $("#com-alert");
    var ahtml = alr.html();

    var _tip = function (options, sec) {
        alr.html(ahtml);    // 复原
        alr.find('.ok').hide();
        alr.find('.cancel').hide();
        alr.find('.modal-content').width(500);
        _dialog(options, sec);

        return {
            on: function (callback) {
            }
        };
    };

    var _alert = function (options) {
      alr.html(ahtml);  // 复原
      alr.find('.ok').removeClass('btn-success').addClass('btn-primary');
      alr.find('.cancel').hide();
      _dialog(options);

      return {
        on: function (callback) {
          if (callback && callback instanceof Function) {
            alr.find('.ok').click(function () { callback(true) });
          }
        }
      };
    };

    var _confirm = function (options) {
      alr.html(ahtml); // 复原
      alr.find('.ok').removeClass('btn-primary').addClass('btn-success');
      alr.find('.cancel').show();
      _dialog(options);

      return {
        on: function (callback) {
          if (callback && callback instanceof Function) {
            alr.find('.ok').click(function () { callback(true) });
            alr.find('.cancel').click(function () { return; });
          }
        }
      };
    };

    var _dialog = function (options) {
      var ops = {
        msg: "提示内容",
        title: "操作提示",
        btnok: "确定",
        btncl: "取消"
      };

      $.extend(ops, options);

      var html = alr.html().replace(reg, function (node, key) {
        return {
          Title: ops.title,
          Message: ops.msg,
          BtnOk: ops.btnok,
          BtnCancel: ops.btncl
        }[key];
      });

      alr.html(html);
      alr.modal({
        width: 250,
        backdrop: 'static'
      });
    }

    return {
      tip: _tip,
      alert: _alert,
      confirm: _confirm
    }

  }();
});
