<template>
  <router-view v-slot="{ Component, route }">
    <!-- 切换分享链接时重新验证提取码，并清除上一个链接的文件信息。 -->
    <component :is="Component" :key="route.name === 'share-access' ? route.fullPath : undefined" />
  </router-view>
</template>

<script setup>
// 根组件只负责挂载路由出口：
// 登录页是独立整屏，主框架是带侧边栏的布局，两者结构差异大，
// 所以布局写在 Layout.vue 里而不是这里。
</script>
