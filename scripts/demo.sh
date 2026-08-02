#!/usr/bin/env bash
# spring-rbac 端到端演示：全部走网关 :4100，覆盖
#   三档角色(viewer/editor/admin) × 客户域  →  删除审批流  →  admin 直删  →  跨服务审计  →  traceId 链路
# 用法：make demo  （需先 make start；审计异步落库，末尾自动等待）
set -euo pipefail

# 避免沙箱/代理把 localhost 也走代理导致失败
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy 2>/dev/null || true

B="${DEMO_HOST:-http://localhost}"
G="${DEMO_PORT:-4100}"

# 从 JSON 取字段：tok "['token']"  -> json.load(stdin)['token']
tok() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }
# 发请求并只打印 HTTP 状态码
code() { curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" "$@"; }

echo "== 1) 三账号登录（种子账号：admin / user=editor / viewer）=="
ADMIN_TOKEN=$(curl -s --noproxy 127.0.0.1,localhost -X POST "$B:$G/api/login" -H 'content-type: application/json' -d '{"username":"admin","password":"admin123"}' | tok "['token']")
EDITOR_TOKEN=$(curl -s --noproxy 127.0.0.1,localhost -X POST "$B:$G/api/login" -H 'content-type: application/json' -d '{"username":"user","password":"user123"}' | tok "['token']")
VIEWER_TOKEN=$(curl -s --noproxy 127.0.0.1,localhost -X POST "$B:$G/api/login" -H 'content-type: application/json' -d '{"username":"viewer","password":"viewer123"}' | tok "['token']")
echo "  admin/editor/viewer token 长度: ${#ADMIN_TOKEN} / ${#EDITOR_TOKEN} / ${#VIEWER_TOKEN}"

echo
echo "== 2) 三档角色：viewer 纯只读 =="
echo "  viewer 列客户        => HTTP $(code "$B:$G/api/customers" -H "Authorization: Bearer $VIEWER_TOKEN")  (预期 200)"
echo "  viewer 建客户        => HTTP $(code -X POST "$B:$G/api/customers" -H "Authorization: Bearer $VIEWER_TOKEN" -H 'content-type: application/json' -d '{"name":"x"}')  (预期 403)"
echo "  viewer 删客户 #1     => HTTP $(code -X DELETE "$B:$G/api/customers/1" -H "Authorization: Bearer $VIEWER_TOKEN")  (预期 403)"

echo
echo "== 3) editor 建客户（customers:create）=="
CNAME="演示客户-$(date +%s)"
C1=$(curl -s --noproxy 127.0.0.1,localhost -X POST "$B:$G/api/customers" \
  -H "Authorization: Bearer $EDITOR_TOKEN" -H 'content-type: application/json' \
  -d "{\"name\":\"$CNAME\",\"company\":\"演示公司\",\"phone\":\"13800000000\",\"email\":\"demo@example.com\",\"status\":\"lead\"}" | tok "['id']")
echo "  已建客户 id=$C1 (name=$CNAME)  => HTTP $(code -X POST "$B:$G/api/customers" -H "Authorization: Bearer $EDITOR_TOKEN" -H 'content-type: application/json' -d '{"name":"x"}')  (预期 201)"

echo
echo "== 4) editor 删除 -> 无审批权，走删除审批流（预期 202 + approvalId）=="
DEL1=$(curl -s --noproxy 127.0.0.1,localhost -X DELETE "$B:$G/api/customers/$C1" -H "Authorization: Bearer $EDITOR_TOKEN")
echo "  删除响应: $DEL1"
A1=$(echo "$DEL1" | tok "['approvalId']")

echo
echo "== 5) admin 驳回该审批 -> 客户保留 =="
echo "  admin 列审批单 (PENDING):"
curl -s --noproxy 127.0.0.1,localhost "$B:$G/api/approvals" -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -c "
import sys,json
for a in json.load(sys.stdin):
    if a['status']=='PENDING':
        print(f\"    id={a['id']} type={a['type']} target={a['targetName'] or ('#'+str(a['targetId']))} applicant={a['applicant']} status={a['status']}\")"
echo "  admin 驳回单 $A1 => HTTP $(code -X POST "$B:$G/api/approvals/$A1/reject" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'content-type: application/json' -d '{"note":"演示驳回"}')  (预期 200)"
echo "  再查客户 $C1       => HTTP $(code "$B:$G/api/customers/$C1" -H "Authorization: Bearer $ADMIN_TOKEN")  (预期 200，客户还在)"

echo
echo "== 6) editor 再删 -> admin 通过 -> 客户真删 =="
DEL2=$(curl -s --noproxy 127.0.0.1,localhost -X DELETE "$B:$G/api/customers/$C1" -H "Authorization: Bearer $EDITOR_TOKEN")
A2=$(echo "$DEL2" | tok "['approvalId']")
echo "  再次删除响应: $DEL2"
echo "  admin 通过单 $A2   => HTTP $(code -X POST "$B:$G/api/approvals/$A2/approve" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'content-type: application/json' -d '{"note":"演示通过"}')  (预期 200)"
echo "  再查客户 $C1       => HTTP $(code "$B:$G/api/customers/$C1" -H "Authorization: Bearer $ADMIN_TOKEN")  (预期 404，已真删)"

echo
echo "== 7) admin 直删（有 customers:approve，绕过审批）=="
C2=$(curl -s --noproxy 127.0.0.1,localhost -X POST "$B:$G/api/customers" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'content-type: application/json' \
  -d '{"name":"直删演示-'"$(date +%s)"'","status":"customer"}' | tok "['id']")
echo "  admin 建客户 id=$C2"
echo -n "  admin 直删 $C2 => "
curl -s --noproxy 127.0.0.1,localhost -X DELETE "$B:$G/api/customers/$C2" -H "Authorization: Bearer $ADMIN_TOKEN"
echo " (预期 deleted:true，无 approvalId)"
TRACE=$(curl -s --noproxy 127.0.0.1,localhost -D - -o /dev/null -X DELETE "$B:$G/api/customers/$C2" -H "Authorization: Bearer $ADMIN_TOKEN" 2>/dev/null | tr -d '\r' | grep -im1 '^X-Trace-Id:' | awk '{print $2}')
echo "  响应头 X-Trace-Id: ${TRACE:-（未取到，可忽略）}（用它在服务日志中 grep 可串联整条调用链）"

echo
echo "== 8) 跨服务审计（网关异步落库，等待 1s）=="
sleep 1
echo "  admin 查审计（最近 6 条）:"
curl -s --noproxy 127.0.0.1,localhost "$B:$G/api/audit?size=6" -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -c "
import sys,json
for a in json.load(sys.stdin)['content']:
    print(f\"    [{a['createdAt'][11:19] if a['createdAt'] else '?'}] {a['actor']:<10} {a['action']:<22} {a['decision']:<5} {a['status'] or '':<4} trace={a['traceId']}\")"
echo "  viewer 查审计       => HTTP $(code "$B:$G/api/audit" -H "Authorization: Bearer $VIEWER_TOKEN")  (预期 403，audit:read 仅 admin)"

echo
echo "== 9) 链路可追溯说明（traceId 贯穿）=="
echo "  上一步的 X-Trace-Id（$TRACE）已落库在审计记录中，并随请求头贯穿到各业务服务；"
echo "  各服务日志行带 [traceId] 前缀（MDC），排障时可执行："
echo "      grep '${TRACE:-<某 traceId>}' logs/*.log"
echo "  即可把一次请求的网关裁决与下游业务处理串联起来。"

echo
echo "演示结束。前端: http://localhost:3000 （admin/admin123 可看审批与审计页）"
