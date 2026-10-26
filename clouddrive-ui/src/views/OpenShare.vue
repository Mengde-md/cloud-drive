<template>
  <div>
    <el-card shadow="never" class="open-card">
      <template #header>
        <div class="card-head">
          <el-icon color="#409eff"><Link /></el-icon>
          <span>打开别人分享的文件</span>
        </div>
      </template>

      <el-form label-position="top">
        <el-form-item label="把对方发来的内容整段粘进来">
          <el-input
            v-model="rawInput"
            type="textarea"
            :rows="3"
            placeholder="链接和提取码一起粘就行，例如：&#10;链接：http://.../s/abc123 提取码：1234"
            @input="parseInput"
          />
        </el-form-item>

        <div class="paste-row">
          <el-button :icon="DocumentCopy" @click="pasteFromClipboard">从剪贴板粘贴</el-button>
          <el-button v-if="rawInput" text @click="clearAll">清空</el-button>
        </div>

        <!-- 解析结果：自动填好，但仍可手改，避免解析失误时用户被卡住 -->
        <el-form-item label="分享码">
          <el-input v-model="token" placeholder="从上面自动识别，也可手动填" />
        </el-form-item>

        <el-form-item label="提取码">
          <el-input v-model="passcode" placeholder="从上面自动识别，也可手动填"
                    maxlength="32" @keyup.enter="lookup" />
        </el-form-item>

        <el-button type="primary" :loading="loading" :icon="Search" @click="lookup">
          查看文件
        </el-button>
      </el-form>

      <p class="cd-muted open-tip">
        整段粘贴即可，不用自己拆分。带提取码的免填链接（形如 .../s/xxx?p=1234）
        也能识别，对方直接点那种链接甚至不用来这一页。
      </p>
    </el-card>

    <!-- 查到文件后展示信息，再决定下载还是转存 -->
    <el-card v-if="info" shadow="never" class="result-card">
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

      <div class="file-actions">
        <el-button type="primary" :icon="Download" :loading="downloading" @click="download">
          下载
        </el-button>
        <el-button :icon="FolderAdd" :loading="saving" @click="save">
          转存到我的网盘
        </el-button>
      </div>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="save-note"
        title="转存后可在「我的文件」中查看，文件会按实际大小占用你的存储空间。"
      />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Document, DocumentCopy, Download, FolderAdd, Link, Search } from '@element-plus/icons-vue'
import * as shareApi from '../api/share'
import { formatBytes, formatTime, saveBlob } from '../utils'

const route = useRoute()

const rawInput = ref('')
const token = ref('')
const passcode = ref('')
const info = ref(null)
const loading = ref(false)
const downloading = ref(false)
const saving = ref(false)

onMounted(() => {
  // 从别处带参数跳过来时直接填上
  if (route.query.token) {
    rawInput.value = String(route.query.token)
    parseInput()
  }
})

/** 粘贴内容一变就解析，用户不用点任何"解析"按钮 */
function parseInput() {
  const parsed = shareApi.extractShare(rawInput.value)
  if (parsed.token) token.value = parsed.token
  if (parsed.passcode) passcode.value = parsed.passcode
}

async function pasteFromClipboard() {
  try {
    // readText 需要安全上下文和用户授权；非 HTTPS 的 IP 访问下不可用
    const text = await navigator.clipboard.readText()
    if (!text) {
      ElMessage.warning('剪贴板是空的')
      return
    }
    rawInput.value = text
    parseInput()
    if (token.value) {
      ElMessage.success(passcode.value ? '已识别分享码和提取码' : '已识别分享码，请补上提取码')
    } else {
      ElMessage.warning('没能从剪贴板内容里识别出分享码')
    }
  } catch (error) {
    ElMessage.warning('浏览器不允许自动读取剪贴板，请手动 Ctrl+V 粘到上面的框里')
  }
}

function clearAll() {
  rawInput.value = ''
  token.value = ''
  passcode.value = ''
  info.value = null
}

async function lookup() {
  if (!token.value.trim()) {
    ElMessage.warning('没能识别出分享码，请检查粘贴的内容是否完整')
    return
  }
  if (!passcode.value.trim()) {
    ElMessage.warning('请输入提取码')
    return
  }

  loading.value = true
  info.value = null
  try {
    info.value = await shareApi.getShareInfo(token.value.trim(), passcode.value.trim())
  } catch (error) {
    // 404 统一表示"分享不存在 / 已过期 / 提取码错误"，拦截器已弹提示。
    // 后端刻意不区分原因，避免别人靠响应差异枚举有效链接。
  } finally {
    loading.value = false
  }
}

async function download() {
  downloading.value = true
  try {
    const response = await fetch(
      shareApi.sharedDownloadUrl(token.value.trim(), passcode.value.trim()))
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
    const saved = await shareApi.saveShare(
      token.value.trim(), passcode.value.trim(), null, null)
    ElMessage.success(`已转存到我的文件：${saved.displayName}`)
  } catch (error) {
    // 同名冲突 409、配额不足 409 都由拦截器提示
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.open-card,
.result-card {
  max-width: 560px;
}

.result-card {
  margin-top: 16px;
}

.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.paste-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: -10px 0 18px;
}

.open-tip {
  margin: 12px 0 0;
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

.file-actions {
  margin-top: 16px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.save-note {
  margin-top: 16px;
  line-height: 1.6;
}
</style>
