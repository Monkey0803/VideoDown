package com.videodown.douyin.presentation.browser

/**
 * 内置网页里的两件「页面侧」适配：隐藏挡住内容的 App 推广弹窗、桌面版 UA。
 *
 * 为什么放在 presentation：这两件事只影响这里的 WebView 呈现，不参与解析与下载，也不进 domain。
 * 边界：只按**文案**命中已知的「打开 App」弹窗，不碰登录墙、不隐藏其它弹窗，也不拦截任何请求——
 * 站点改版后脚本失效时最坏情况就是「和没有它一样」，不会把页面弄坏。
 */
internal object PageTweaks {

    /** 桌面版网页使用的 UA。固定字符串便于设备用例断言「真的切过去了」。 */
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * 判定「打开 App」弹窗的文案（小写比较）。
     *
     * 只收真正会挡住帖子正文的那几句：X 的入口弹窗是中英混排两套文案，这里都认；没有命中的弹窗
     * 一律不动，避免把回复框、登录框之类误杀。
     */
    val appPromptMarkers = listOf(
        "see this post in the app",
        "open this post in the app",
        "open the app to see",
        "在 app 中查看", // l10n-ignore: 网页上的弹窗文案，用于匹配，不展示给用户
        "在app中查看", // l10n-ignore: 网页上的弹窗文案，用于匹配
        "打开 app 查看", // l10n-ignore: 网页上的弹窗文案，用于匹配
        "打开app查看", // l10n-ignore: 网页上的弹窗文案，用于匹配
    )

    /**
     * 注入脚本：隐藏命中文案的 `role=dialog` / `aria-modal`，并解除它留下的滚动锁。
     *
     * X 的弹窗挂载得比 `onPageFinished` 晚，所以除了立即执行一次，还挂 8 秒的 MutationObserver；
     * `window.__syPromptHider` 防重复注入。全程 try/catch——脚本失败不能让网页本身出错。
     */
    fun appPromptHiderScript(): String {
        val markers = appPromptMarkers.joinToString(", ") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
        return """
            (function () {
              try {
                var markers = [$markers];
                var hidden = false;
                function unlockScroll() {
                  ['body', 'html'].forEach(function (tag) {
                    var root = document.querySelector(tag);
                    if (root && getComputedStyle(root).overflow === 'hidden') {
                      root.style.setProperty('overflow', 'auto', 'important');
                    }
                  });
                }
                function kill() {
                  var nodes = document.querySelectorAll('[role="dialog"], [aria-modal="true"], [data-testid="sheetDialog"]');
                  for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    var text = (el.innerText || '').toLowerCase();
                    for (var m = 0; m < markers.length; m++) {
                      if (text.indexOf(markers[m]) !== -1) {
                        if (el.style.display !== 'none') {
                          el.style.setProperty('display', 'none', 'important');
                          hidden = true;
                        }
                        break;
                      }
                    }
                  }
                  if (hidden) unlockScroll();
                }
                kill();
                if (!window.__syPromptHider) {
                  window.__syPromptHider = true;
                  var observer = new MutationObserver(kill);
                  observer.observe(document.documentElement, { childList: true, subtree: true });
                  window.setTimeout(function () { observer.disconnect(); }, 8000);
                }
              } catch (e) {}
            })();
        """.trimIndent()
    }
}
