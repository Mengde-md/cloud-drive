import { computed, onMounted, onUnmounted, ref } from 'vue'

/** 低于这个宽度按手机处理。768 是常见的平板竖屏分界点。 */
const MOBILE_MAX_WIDTH = 768

/**
 * 响应式断点。
 *
 * <p>为什么用 JS 判断而不是纯 CSS 媒体查询：手机上表格（el-table）不是"变窄"就能用的，
 * 它需要换成完全不同的卡片结构。这种结构级差异必须让组件知道当前是不是手机，
 * 纯 CSS 只能做到隐藏/显示两套 DOM，会白白渲染一份用不到的。</p>
 *
 * <p>监听器在 onUnmounted 里必须移除，否则每次路由切换都会累积一个 resize 回调，
 * 页面越用越卡——这是单页应用里最常见的内存泄漏来源。</p>
 */
export function useBreakpoint() {
  const width = ref(window.innerWidth)

  const update = () => {
    width.value = window.innerWidth
  }

  onMounted(() => window.addEventListener('resize', update))
  onUnmounted(() => window.removeEventListener('resize', update))

  const isMobile = computed(() => width.value < MOBILE_MAX_WIDTH)

  /** 弹窗宽度：手机上用百分比，桌面用固定像素 */
  const dialogWidth = (desktopWidth = '460px') => (isMobile.value ? '92%' : desktopWidth)

  return { width, isMobile, dialogWidth }
}
