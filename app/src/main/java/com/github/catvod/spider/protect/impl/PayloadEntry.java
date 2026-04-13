package com.github.catvod.spider.protect.impl;

import com.github.catvod.spider.protect.RealLeodanmu;

/**
 * 第三刀：payload 主执行入口。
 * 当前先继承稳定的 RealLeodanmu，让 loader 的主路径正式切到 payload-only 入口类名。
 * 后续继续把真实实现逐步从 fallback 层收进来。
 */
public class PayloadEntry extends RealLeodanmu {
}
