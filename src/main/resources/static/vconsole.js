// https://github.com/Tencent/vConsole

function intPlugin() {
  if (window.location.href.indexOf('dev=true') > -1) {
    var script = document.createElement('script')
    var firstScript = document.getElementsByTagName('script')[0]
    script.async = 1
    script.src =
      ('https:' === document.location.protocol ? 'https://' : 'http://') +
      'cdn.bootcss.com/vConsole/3.3.2/vconsole.min.js'
    script.onload = function () {
      // 显示 vConsole
      var vConsole = new window.VConsole()
      vConsole.show()
    }
    firstScript.parentNode.insertBefore(script, firstScript)
  }
}
intPlugin()

// <style>
//     #__vconsole .vc-switch {
//       bottom: 100px !important;
//     }
// </style>
