<template>
  <div class="share-page">
    <div class="share-card">
      <div class="share-brand">
        <el-icon :size="30" color="#409eff"><Share /></el-icon>
        <h1>有人给你分享了一个文件</h1>
        <p class="cd-muted">输入提取码即可下载，无需注册或登录</p>
      </div>

      <template v-if="!info">
        <el-input
          v-model="passcode"
          placeholder="请输入提取码"
          size="large"
          maxlength="32"
          @keyup.enter="lookup"
        >
          <template #prefix>
            <el-icon><Key /></el-icon>
          </template>
        </el-input>

        <el-button type="primary" size="large" class="share-submit" :loading="loading" @click="lookup">
          确 定
        </el-button>

        <p class="cd-muted share-tip">
          提取码由分享者提供。如果无法打开，请向分享者确认链接是否有效。
        </p>
      </template>

      <template v-else>
        <div class="file-row">
          <el-icon :size="30" color="#409eff"><Document /></el-icon>
          <div class="file-meta">
            <div class="file-name">{{ info.displayName }}</div>
            <div class="cd-muted">
              {{ formatBytes(info.byteSize) }}
              ·
              {{ info.expiresAt ? `有效至 ${formatTime(info.expiresAt)}` : '长期有效' }}
            </div>
          </div>
        </div>

        <el-button type="primary" size="large" class="share-submit" :loading="downloading"
                   :icon="Download" @click="download">
          下载文件
        </el-button>

        <el-button v-if="loggedIn" size="large" class="share-submit secondary"
                   :loading="saving" :icon="FolderAdd" @click="save">
          转存到我的网盘
        </el-button>

        <p v-else class="cd-muted share-tip">
          想把它保存到自己的网盘？
          <router-link to="/login">登录</router-link>
          后即可转存，文件会占用你的存储空间。
        </p>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Document, Download, FolderAdd, Key, Share } from '@element-plus/icons-vue'
import * as shareApi from '../api/share'
import { formatBytes, formatTime, saveBlob } from '../utils'

const route = useRoute()

const passcode = ref('')
const info = ref(null)
const loading = ref(false)
const downloading = ref(false)
const saving = ref(false)

const token = computed(() => String(route.params.token || ''))

// 免填链接：.../s/xxx?p=1234 —— 提取码带在查询参数里，
// 对方点开就直接看到文件，一个字都不用贴。
// 代价是提取码会进浏览器历史、可能出现在 Referer 和服务端访问日志里，
// 所以分享方可以自己选要不要用这种链接。
onMounted(() => {
  const fromQuery = route.query.p
  if (fromQuery) {
    passcode.value = String(fromQuery)
    lookup()
  }
})
// 这个页面是匿名可访问的，所以不能依赖 Pinia 的登录态守卫，
// 直接看本地有没有令牌来决定是否显示"转存"。
const loggedIn = computed(() => Boolean(localStorage.getItem('cd_token')))

async function lookup() {
  if (!passcode.value.trim()) {
    ElMessage.warning('请输入提取码')
    return
  }
  loading.value = true
  try {
    // 这里用 axios 实例（会带上令牌，但后端这个接口是匿名的，带不带都行），
    // 好处是能复用统一的错误提示。
    info.value = await shareApi.getShareInfo(token.value, passcode.value.trim())
  } catch (error) {
    // 拦截器已提示"分享不存在或提取码错误"
  } finally {
    loading.value = false
  }
}

async function download() {
  downloading.value = true
  try {
    // 下载走原生 fetch 而不是 axios：这个接口返回的是文件流，
    // 用 fetch 能直接拿 blob，也能读到 Content-Disposition 里的文件名。
    const response = await fetch(shareApi.sharedDownloadUrl(token.value, passcode.value.trim()))
    if (!response.ok) {
      ElMessage.error('分享已失效，请重新确认链接和提取码')
      return
    }
    saveBlob(await response.blob(), info.value.displayName)
    ElMessage.success('开始下载')
  } catch (error) {
    ElMessage.error('无法连接服务器')
  } finally {
    downloading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const saved = await shareApi.saveShare(token.value, passcode.value.trim(), null, null)
    ElMessage.success(`已转存到我的文件：${saved.displayName}`)
  } catch (error) {
    // 同名冲突、配额不足均由拦截器提示
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.share-page {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 0;
  background: linear-gradient(135deg, #e8f1fb 0%, #f5f7fa 100%);
}

.share-card {
  width: 400px;
  max-width: calc(100vw - 32px);
  padding: 32px 36px 28px;
  box-sizing: border-box;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.08);
}

.share-brand {
  text-align: center;
  margin-bottom: 20px;
}

.share-brand h1 {
  font-size: 18px;
  margin: 10px 0 4px;
}

.share-brand p {
  margin: 0;
}

.share-submit {
  width: 100%;
  margin-top: 16px;
}

.share-submit.secondary {
  margin-left: 0;
  margin-top: 10px;
}

.share-tip {
  margin: 18px 0 0;
  line-height: 1.6;
}

.file-row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.file-meta {
  min-width: 0;
}

.file-name {
  font-size: 15px;
  font-weight: 500;
  overflow-wrap: anywhere;
}

@media (max-width: 480px) {
  .share-card {
    padding: 24px 20px 20px;
  }
}
</style>
