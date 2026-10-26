<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <el-icon :size="34" color="#409eff"><FolderOpened /></el-icon>
        <h1>网络云盘</h1>
        <p class="cd-muted">保存文件，随时整理与分享</p>
      </div>
      <el-tabs v-model="mode" stretch>
        <el-tab-pane label="登录" name="login" :disabled="busy" />
        <el-tab-pane label="注册" name="register" :disabled="busy" />
      </el-tabs>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="3–64 个字符" autocomplete="username" clearable maxlength="64" />
        </el-form-item>
        <template v-if="mode === 'register'">
          <el-form-item label="邮箱" prop="email">
            <el-input v-model="form.email" type="email" placeholder="请输入本人可收信的邮箱" autocomplete="email" maxlength="254" clearable />
          </el-form-item>
          <el-form-item label="邮箱验证码" prop="code">
            <div class="code-row">
              <el-input v-model="form.code" placeholder="6 位验证码" inputmode="numeric" autocomplete="one-time-code" maxlength="6" />
              <el-button :loading="sending" :disabled="cooldown > 0 || loading || resetting" @click="sendCode">
                {{ cooldown > 0 ? `${cooldown} 秒后重发` : '发送验证码' }}
              </el-button>
            </div>
            <div v-if="codeNotice" class="cd-muted code-notice">{{ codeNotice }}</div>
          </el-form-item>
        </template>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="8–72 个字符" show-password
                    :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" maxlength="72" />
        </el-form-item>
        <el-form-item v-if="mode === 'register'" label="确认密码" prop="confirm">
          <el-input v-model="form.confirm" type="password" placeholder="再次输入密码" show-password autocomplete="new-password" maxlength="72" />
        </el-form-item>
        <el-button type="primary" size="large" native-type="submit" class="login-submit" :loading="loading" :disabled="sending || resetting">
          {{ mode === 'login' ? '登录' : '注册并登录' }}
        </el-button>
      </el-form>
      <p v-if="mode === 'register'" class="cd-muted login-tip">验证邮箱后即可注册。此邮箱也用于找回密码。</p>
      <template v-if="mode === 'login'">
        <div class="forgot-password">
          <el-button link type="primary" :disabled="busy" :aria-expanded="resetExpanded" aria-controls="password-recovery" @click="resetExpanded = !resetExpanded">找回密码</el-button>
        </div>
        <el-collapse-transition>
          <section v-show="resetExpanded" id="password-recovery" class="password-recovery" aria-label="邮箱找回密码">
            <h2>邮箱找回密码</h2>
            <p class="cd-muted recovery-tip">使用注册邮箱验证身份，设置新密码后再登录。</p>
            <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-position="top" @submit.prevent="submitReset">
              <el-form-item label="注册邮箱" prop="email">
                <el-input v-model="resetForm.email" type="email" placeholder="请输入注册时使用的邮箱" autocomplete="email" maxlength="254" clearable />
              </el-form-item>
              <el-form-item label="邮箱验证码" prop="code">
                <div class="code-row">
                  <el-input v-model="resetForm.code" placeholder="6 位验证码" inputmode="numeric" autocomplete="one-time-code" maxlength="6" />
                  <el-button :loading="sending" :disabled="resetCooldown > 0 || loading || resetting" @click="sendResetCode">{{ resetCooldown > 0 ? `${resetCooldown} 秒后重发` : '发送验证码' }}</el-button>
                </div>
                <div v-if="resetCodeNotice" class="cd-muted code-notice">{{ resetCodeNotice }}</div>
              </el-form-item>
              <el-form-item label="新密码" prop="password">
                <el-input v-model="resetForm.password" type="password" placeholder="8–72 个字符" show-password autocomplete="new-password" maxlength="72" />
              </el-form-item>
              <el-form-item label="确认新密码" prop="confirm">
                <el-input v-model="resetForm.confirm" type="password" placeholder="再次输入新密码" show-password autocomplete="new-password" maxlength="72" />
              </el-form-item>
              <el-button type="primary" native-type="submit" class="login-submit" :loading="resetting" :disabled="loading || sending">重置密码</el-button>
            </el-form>
          </section>
        </el-collapse-transition>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import * as authApi from '../api/auth'

const router = useRouter()
const userStore = useUserStore()
const mode = ref('login')
const loading = ref(false)
const resetting = ref(false)
const sending = ref(false)
const busy = computed(() => loading.value || resetting.value || sending.value)
const formRef = ref()
const resetFormRef = ref()
const resetExpanded = ref(false)
const codeNotice = ref('')
const resetCodeNotice = ref('')
const form = reactive({ username: '', password: '', confirm: '', email: '', code: '' })
const resetForm = reactive({ email: '', code: '', password: '', confirm: '' })
const now = ref(Date.now())
const sentUntil = reactive({})
const keyFor = (purpose, email) => `${purpose}:${email.trim().toLowerCase()}`
const cooldownKey = computed(() => keyFor('REGISTER', form.email))
const cooldown = computed(() => Math.max(0, Math.ceil(((sentUntil[cooldownKey.value] || 0) - now.value) / 1000)))
const resetCooldown = computed(() => Math.max(0, Math.ceil(((sentUntil[keyFor('RESET_PASSWORD', resetForm.email)] || 0) - now.value) / 1000)))
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)
onBeforeUnmount(() => window.clearInterval(timer))

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 64, message: '用户名需为 3–64 个字符', trigger: 'blur' }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' }
  ],
  code: [{ pattern: /^\d{6}$/, required: true, message: '请输入 6 位邮箱验证码', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 72, message: '密码需为 8–72 个字符', trigger: 'blur' }
  ],
  confirm: [{ required: true, validator: (rule, value, callback) => {
    callback(value === form.password && value ? undefined : new Error('两次输入的密码不一致'))
  }, trigger: 'blur' }]
}
const resetRules = {
  email: rules.email,
  code: rules.code,
  password: rules.password,
  confirm: [{ required: true, validator: (rule, value, callback) => {
    callback(value === resetForm.password && value ? undefined : new Error('两次输入的新密码不一致'))
  }, trigger: 'blur' }]
}

watch(mode, () => {
  resetExpanded.value = false
  form.password = ''
  form.confirm = ''
  form.code = ''
  codeNotice.value = ''
  formRef.value?.clearValidate()
})
watch(() => form.email, () => { form.code = ''; codeNotice.value = '' })
watch(() => resetForm.email, () => { resetForm.code = ''; resetCodeNotice.value = '' })
watch(resetExpanded, (expanded) => {
  if (!expanded) {
    resetForm.password = ''
    resetForm.confirm = ''
    resetForm.code = ''
    resetCodeNotice.value = ''
    resetFormRef.value?.clearValidate()
  }
})

async function sendCode() {
  await sendEmailCodeFor(formRef.value, form, 'REGISTER', codeNotice)
}
async function sendResetCode() {
  await sendEmailCodeFor(resetFormRef.value, resetForm, 'RESET_PASSWORD', resetCodeNotice)
}
async function sendEmailCodeFor(refValue, fields, purpose, notice) {
  if (busy.value) return
  fields.email = fields.email.trim()
  const key = keyFor(purpose, fields.email)
  if ((sentUntil[key] || 0) > Date.now()) return
  if (!await refValue.validateField('email').catch(() => false)) return
  const targetEmail = fields.email
  sending.value = true
  try {
    const result = await authApi.sendEmailCode(targetEmail, purpose)
    now.value = Date.now()
    sentUntil[key] = now.value + (result.retryAfterSeconds || 60) * 1000
    if (fields.email === targetEmail) {
      notice.value = result.message || '验证码已发送，请检查收件箱和垃圾邮件。'
    }
    ElMessage.success(result.message || '验证码已发送，请查收邮箱')
  } catch (error) {
    // 统一显示服务器返回的提示。
  } finally { sending.value = false }
}

async function submit() {
  if (busy.value) return
  if (!await formRef.value.validate().catch(() => false)) return
  const submittedMode = mode.value
  const submitted = { ...form, username: form.username.trim(), email: form.email.trim() }
  loading.value = true
  try {
    if (submittedMode === 'register') {
      await userStore.register(submitted.username, submitted.password, submitted.email, submitted.code)
      ElMessage.success('注册成功')
    }
    await userStore.login(submitted.username, submitted.password)
    await router.push({ name: 'files' })
  } catch (error) {
    // 统一显示服务器返回的提示。
  } finally { loading.value = false }
}

async function submitReset() {
  if (busy.value || !resetExpanded.value) return
  if (!await resetFormRef.value.validate().catch(() => false)) return
  const submitted = { ...resetForm, email: resetForm.email.trim() }
  resetting.value = true
  try {
    await authApi.resetPassword(submitted.email, submitted.code, submitted.password)
    form.password = ''
    resetExpanded.value = false
    ElMessage.success('密码已重置，请使用新密码登录')
  } catch (error) {
    // 统一显示服务器返回的提示，保留表单以便修正。
  } finally { resetting.value = false }
}
</script>

<style scoped>
.login-page { min-height: 100%; display: flex; align-items: center; justify-content: center; padding: 24px 0; box-sizing: border-box; background: linear-gradient(135deg, #e8f1fb 0%, #f5f7fa 100%); }
.login-card { width: 420px; max-width: calc(100vw - 32px); padding: 28px 32px; box-sizing: border-box; background: #fff; border-radius: 12px; box-shadow: 0 8px 28px rgba(0,0,0,.08); }
.login-brand { text-align: center; margin-bottom: 16px; }
.login-brand h1 { font-size: 22px; margin: 10px 0 6px; }
.login-brand p { margin: 0; }
.login-submit { width: 100%; margin-top: 4px; }
.login-tip { margin: 18px 0 0; line-height: 1.7; text-align: center; }
.forgot-password { margin-top: 12px; text-align: right; }
.password-recovery { margin-top: 16px; padding-top: 20px; border-top: 1px solid #ebeef5; }
.password-recovery h2 { margin: 0 0 8px; font-size: 16px; }
.recovery-tip { margin: 0 0 18px; line-height: 1.7; }
.code-row { display: flex; gap: 8px; width: 100%; }
.code-row .el-input { min-width: 0; }
.code-notice { width: 100%; line-height: 1.6; margin-top: 6px; }
@media (max-width: 480px) { .login-card { padding: 24px 20px; } }
</style>
