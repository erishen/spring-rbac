#!/usr/bin/env bash
# spring-rbac 端到端演示：全部走网关 :4100，跑完整 RBAC 链路
# 用法：make demo  （需先 make start）
set -euo pipefail

# 避免沙箱/代理把 localhost 也走代理导致失败
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy 2>/dev/null || true

B="${DEMO_HOST:-http://localhost}"
G="${DEMO_PORT:-4100}"

# 从 JSON 取字段：tok "['token']"  -> json.load(stdin)['token']
tok() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

echo "== 1) admin 登录 =="
ADMIN_TOKEN=$(curl -s --noproxy 127.0.0.1,localhost -X POST "$B:$G/api/login" -H 'content-type: application/json' -d '{"username":"admin","password":"admin123"}' | tok "['token']")
echo "  token 长度: ${#ADMIN_TOKEN}"

echo "== 2) admin 查看自身 (/api/me) =="
curl -s --noproxy 127.0.0.1,localhost "$B:$G/api/me" -H "Authorization: Bearer $ADMIN_TOKEN"; echo

echo "== 3) admin 建角色 auditor (POST /api/roles) =="
curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "  HTTP %{http_code}\n" -X POST "$B:$G/api/roles" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'content-type: application/json' -d '{"name":"auditor"}'

echo "== 4) 注册 alice (POST /api/register) =="
curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "  HTTP %{http_code}\n" -X POST "$B:$G/api/register" -H 'content-type: application/json' -d '{"username":"alice","password":"alice123"}'

echo "== 5) 把 viewer(继承 user) 授予 alice =="
VIEWER_ID=$(curl -s --noproxy 127.0.0.1,localhost "$B:$G/api/roles" -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -c "import sys,json;d=json.load(sys.stdin);print([r['id'] for r in d if r['name']=='viewer'][0])")
echo "  viewer roleId=$VIEWER_ID"
curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "  HTTP %{http_code}\n" -X POST "$B:$G/api/users/alice/roles" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'content-type: application/json' -d "{\"roleId\":$VIEWER_ID}"

echo "== 6) alice 登录 =="
ALICE_TOKEN=$(curl -s --noproxy 127.0.0.1,localhost -X POST "$B:$G/api/login" -H 'content-type: application/json' -d '{"username":"alice","password":"alice123"}' | tok "['token']")
echo "  token 长度: ${#ALICE_TOKEN}"

echo "== 7) alice 列角色 (roles:read 放行，预期 200) =="
curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "  HTTP %{http_code}\n" "$B:$G/api/roles" -H "Authorization: Bearer $ALICE_TOKEN"

echo "== 8) alice 建角色 (roles:write 拒绝，预期 403) =="
curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "  HTTP %{http_code}\n" -X POST "$B:$G/api/roles" -H "Authorization: Bearer $ALICE_TOKEN" -H 'content-type: application/json' -d '{"name":"x"}'

echo "== 9) alice 有效权限判定 (经 rbac /api/check) =="
echo -n "  roles:read  => "; curl -s --noproxy 127.0.0.1,localhost "$B:$G/api/check?user=alice&permission=roles:read" -H "Authorization: Bearer $ALICE_TOKEN"; echo
echo -n "  roles:write => "; curl -s --noproxy 127.0.0.1,localhost "$B:$G/api/check?user=alice&permission=roles:write" -H "Authorization: Bearer $ALICE_TOKEN"; echo

echo "== 10) 无 token 访问 (预期 401) =="
curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "  HTTP %{http_code}\n" "$B:$G/api/roles"

echo "演示结束。"
