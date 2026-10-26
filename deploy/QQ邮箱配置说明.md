# QQ 邮箱发送注册与找回密码验证码

系统需要一个发件邮箱，注册表单中填写的邮箱是收件邮箱，两者用途不同。

1. 登录 QQ 邮箱网页版，在设置中找到 SMTP/IMAP 服务，开启服务并生成授权码。
2. 打开 `clouddrive/local.env.ps1`，填写 `CLOUD_DRIVE_SMTP_USERNAME`（完整 QQ 邮箱地址）和 `CLOUD_DRIVE_SMTP_PASSWORD`（SMTP 授权码）。该私有文件已被 Git 忽略。
3. QQ 的其余连接参数已设置为 `smtp.qq.com`、465、SSL 开启、STARTTLS 关闭，发件人地址自动使用同一个 QQ 邮箱。
4. 保存配置后重启后端，已有运行进程不会自动读取环境变量的变更。

当前预览使用独立数据库，重启时使用 `clouddrive/build-output/preview/start-preview.ps1`，保留预览 MySQL/Redis 进程；先停止原来的 8080 后端，再运行脚本。首次切换启动方式后需重新登录。

正式本机环境使用 `clouddrive/run.ps1`，数据库信息按实际环境配置。服务器部署时把同名 SMTP 环境变量写入服务器私有配置，再重启服务。

QQ 邮箱地址或授权码未填写时，发送验证码会明确提示“邮箱服务未配置”。填写后若仍发送失败，应检查 SMTP 服务是否开启、授权码是否有效和网络是否允许连接 465 端口。

连接参数和授权码用途参考 [腾讯官方 SMTP 参数说明](https://cloud.tencent.com/document/product/876/34822)。授权码只填写在本地或服务器的私有配置里。
