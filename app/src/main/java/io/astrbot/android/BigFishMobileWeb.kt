// SPDX-License-Identifier: MIT
package io.astrbot.android

/** Visual adjustments for our local backend only. Does not inspect messages or change requests. */
internal object BigFishMobileWeb {
    val script = """
        (() => {
          if (!document.head || document.getElementById('bigfish-phone-style')) return;
          const style = document.createElement('style');
          style.id = 'bigfish-phone-style';
          style.textContent = `
            @media (max-width: 720px) {
              html { -webkit-text-size-adjust: 100%; scroll-padding-bottom: 80px; }
              body { overscroll-behavior-y: contain; }
              button, .v-btn, .el-button { min-height: 44px; touch-action: manipulation; }
              input:not([type=checkbox]):not([type=radio]):not([type=range]):not([type=file]):not([type=hidden]), textarea, select {
                font-size: 16px !important; font-size: max(16px, 1em) !important;
              }
              .v-main { min-width: 0 !important; }
              .v-dialog > .v-overlay__content { margin: 12px !important; max-width: calc(100vw - 24px) !important; max-height: calc(100vh - 24px) !important; }
              .v-table, .el-table { overflow-x: auto; max-width: 100%; }
              pre { max-width: 100%; overflow-x: auto; white-space: pre-wrap; word-break: break-word; }
              img, video { max-width: 100%; }
            }
          `;
          document.head.appendChild(style);
        })();
    """.trimIndent()
}
