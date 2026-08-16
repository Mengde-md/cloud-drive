#!/bin/bash
# ============================================================
# VM 端中间件配置脚本（在 Ubuntu VM 内执行）
# 作用：让主机能连到 VM 里的 MySQL / Redis / Nacos
# 用法：chmod +x vm-setup.sh && sudo ./vm-setup.sh
# ============================================================
set -e

echo "====== 1. MySQL 允许远程连接 ======"

# 1.1 修改 bind-address
MYSQL_CONF=""
if [ -f /etc/mysql/mysql.conf.d/mysqld.cnf ]; then
    MYSQL_CONF=/etc/mysql/mysql.conf.d/mysqld.cnf
elif [ -f /etc/mysql/my.cnf ]; then
    MYSQL_CONF=/etc/mysql/my.cnf
elif [ -f /etc/my.cnf ]; then
    MYSQL_CONF=/etc/my.cnf
fi

if [ -n "$MYSQL_CONF" ]; then
    sed -i 's/bind-address.*/bind-address = 0.0.0.0/' "$MYSQL_CONF"
    echo "   已修改 $MYSQL_CONF -> bind-address = 0.0.0.0"
else
    echo "   ⚠️ 找不到 MySQL 配置文件，请手动改 bind-address = 0.0.0.0"
fi

# 1.2 授权 root 远程登录（测试用，生产建专用账号）
echo ""
echo "   接下来需要输入 MySQL root 寘认密码（如果没设密码直接回车）"
read -s -p "   MySQL root 密码: " MYSQL_PWD
mysql -u root -p"$MYSQL_PWD" <<EOF
CREATE USER IF NOT EXISTS 'root'@'%' IDENTIFIED BY '${MYSQL_PWD}';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' WITH GRANT OPTION;
FLUSH PRIVILEGES;
EOF
echo "   ✅ root@% 已授权"

systemctl restart mysql 2>/dev/null || systemctl restart mysqld 2>/dev/null || systemctl restart mariadb 2>/dev/null
echo "   ✅ MySQL 已重启"

echo ""
echo "====== 2. Redis 允许远程连接 ======"

REDIS_CONF=""
if [ -f /etc/redis/redis.conf ]; then
    REDIS_CONF=/etc/redis/redis.conf
elif [ -f /etc/redis.conf ]; then
    REDIS_CONF=/etc/redis.conf
fi

if [ -n "$REDIS_CONF" ]; then
    # bind 0.0.0.0
    sed -i 's/^bind 127.0.0.1/bind 0.0.0.0/' "$REDIS_CONF"
    # 设密码
    sed -i 's/^# requirepass foobared/requirepass 123456/' "$REDIS_CONF"
    # 如果没有 requirepass 行就追加
    grep -q '^requirepass' "$REDIS_CONF" || echo 'requirepass 123456' >> "$REDIS_CONF"
    # 关闭保护模式（设了密码后可以关）
    sed -i 's/^protected-mode yes/protected-mode no/' "$REDIS_CONF"
    echo "   已修改 $REDIS_CONF"
    echo "   - bind 0.0.0.0"
    echo "   - requirepass 123456"
    echo "   - protected-mode no"
else
    echo "   ⚠️ 找不到 redis.conf，请手动配置"
fi

systemctl restart redis-server 2>/dev/null || systemctl restart redis 2>/dev/null
echo "   ✅ Redis 已重启"

echo ""
echo "====== 3. Nacos 检查 ======"
# Nacos 默认监听 0.0.0.0，一般不需要改
if curl -sf http://127.0.0.1:8848/nacos/ -o /dev/null 2>&1; then
    echo "   ✅ Nacos 正在运行"
else
    echo "   ⚠️ Nacos 未运行，请先启动 Nacos"
    echo "      如果用 Docker："
    echo "      docker run -d --name nacos -p 8848:8848 -p 9848:9848 \\"
    echo "        -e MODE=standalone -e JVM_XMS=512m -e JVM_XMX=512m \\"
    echo "        nacos/nacos-server:v2.4.3"
fi

echo ""
echo "====== 4. 防火墙放行端口 ======"
if command -v ufw &> /dev/null; then
    ufw allow 3306/tcp
    ufw allow 6379/tcp
    ufw allow 8848/tcp
    ufw allow 9848/tcp
    echo "   ✅ ufw 已放行 3306/6379/8848/9848"
elif command -v firewall-cmd &> /dev/null; then
    firewall-cmd --permanent --add-port=3306/tcp
    firewall-cmd --permanent --add-port=6379/tcp
    firewall-cmd --permanent --add-port=8848/tcp
    firewall-cmd --permanent --add-port=9848/tcp
    firewall-cmd --reload
    echo "   ✅ firewalld 已放行 3306/6379/8848/9848"
else
    echo "   ℹ️ 未检测到防火墙工具，跳过"
fi

echo ""
echo "====== 5. 验证 ======"
echo "   MySQL:  从主机执行 mysql -h 192.168.119.128 -u root -p"
echo "   Redis:  从主机执行 redis-cli -h 192.168.119.128 -a 123456 ping"
echo "   Nacos:  从主机浏览器访问 http://192.168.119.128:8848/nacos"
echo ""
echo "====== 全部完成 ======"
