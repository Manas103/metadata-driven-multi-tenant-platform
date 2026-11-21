#!/usr/bin/env bash
# Deploys the service to AWS (deploy/aws/main.tf: ECR, RDS PostgreSQL, ECS Fargate), runs
# scripts/remote_check.py against it from this machine, copies the results to results/aws/,
# then destroys everything and checks that nothing is left. The destroy runs on any exit.
#
#   AWS=aws scripts/aws_run.sh        (AWS: the aws CLI to use; credentials from `aws login`)
set -euo pipefail
cd "$(dirname "$0")/.."
AWS=${AWS:-aws}
REGION=${REGION:-us-east-1}
RUN=${RUN_ID:-r$(date +%m%d%H%M)}
OUT=results/aws
TF="terraform -chdir=deploy/aws"
mkdir -p "$OUT"

creds() { eval "$("$AWS" configure export-credentials --format env | tr -d '\r')"; export AWS_REGION=$REGION; }
aws_() { "$AWS" --region "$REGION" "$@" | tr -d '\r'; }

MYIP=$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')
VARS=(-var run_id="$RUN" -var allowed_cidr="$MYIP/32")

destroy() {
  set +e
  creds
  $TF destroy -auto-approve -input=false "${VARS[@]}" | tail -2
  # check real state, not the tagging API (it lists deleted resources for a while)
  {
    echo "run $RUN, checked after terraform destroy:"
    echo "ECS clusters active: $(aws_ ecs list-clusters --query "length(clusterArns[?contains(@, 'mtp-$RUN')])" --output text)"
    echo "RDS instances: $(aws_ rds describe-db-instances --query "length(DBInstances[?DBInstanceIdentifier=='mtp-$RUN'])" --output text)"
    echo "ECR repositories: $(aws_ ecr describe-repositories --query "length(repositories[?repositoryName=='metadata-driven-multi-tenant-platform'])" --output text)"
    echo "SSM parameters: $(aws_ ssm describe-parameters --query "length(Parameters[?starts_with(Name, '/mtp-$RUN/')])" --output text)"
    echo "log groups: $(aws_ logs describe-log-groups --log-group-name-prefix "/ecs/mtp-$RUN" --query 'length(logGroups)' --output text)"
    echo "security groups: $(aws_ ec2 describe-security-groups --filters "Name=group-name,Values=mtp-$RUN-*" --query 'length(SecurityGroups)' --output text)"
    echo "network interfaces: $(aws_ ec2 describe-network-interfaces --filters "Name=group-name,Values=mtp-$RUN-*" --query 'length(NetworkInterfaces)' --output text)"
    echo "IAM roles: $(aws_ iam list-roles --query "length(Roles[?RoleName=='mtp-$RUN-execution'])" --output text)"
  } | tee "$OUT/left_after_destroy.txt"
}
trap destroy EXIT

creds
$TF init -input=false >/dev/null
echo "== infrastructure (the database takes several minutes)"
$TF apply -auto-approve -input=false "${VARS[@]}" -var desired_count=0 | tail -1
REPO=$($TF output -raw ecr_repository_url)

echo "== image"
creds
export DOCKER_CONFIG
DOCKER_CONFIG=$(mktemp -d) # the default config may name a credential helper that cannot run here
[ -d "$HOME/.docker/cli-plugins" ] && ln -s "$HOME/.docker/cli-plugins" "$DOCKER_CONFIG/cli-plugins"
aws_ ecr get-login-password | docker login --username AWS --password-stdin "${REPO%%/*}" >/dev/null
docker build -q -t "$REPO:latest" . >/dev/null
docker push -q "$REPO:latest" >/dev/null

echo "== service"
creds
$TF apply -auto-approve -input=false "${VARS[@]}" -var desired_count=1 | tail -1
CLUSTER=$($TF output -raw cluster)
SERVICE=$($TF output -raw service)
T0=$(date +%s)
aws_ ecs wait services-stable --cluster "$CLUSTER" --services "$SERVICE"
TASK=$(aws_ ecs list-tasks --cluster "$CLUSTER" --service-name "$SERVICE" --query 'taskArns[0]' --output text)
ENI=$(aws_ ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK" \
  --query "tasks[0].attachments[0].details[?name=='networkInterfaceId'].value" --output text)
IP=$(aws_ ec2 describe-network-interfaces --network-interface-ids "$ENI" \
  --query 'NetworkInterfaces[0].Association.PublicIp' --output text)
URL="http://$IP:8080"
for i in $(seq 1 60); do
  n=$(curl -fsS "$URL/api/admin/tenants" 2>/dev/null | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' || echo 0)
  [ "$n" -ge 8 ] && break
  sleep 5
done
echo "service up with demo data $(( $(date +%s) - T0 )) s after the task was requested" | tee "$OUT/startup.txt"

echo "== checks"
python3 scripts/remote_check.py "$URL" 500 | tee "$OUT/remote_check.txt"
{
  echo "database: PostgreSQL $($TF output -raw db_engine_version) on RDS db.t4g.micro"
  aws_ ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK" \
    --query 'tasks[0].{launchType:launchType,cpu:cpu,memory:memory,platform:platformVersion,lastStatus:lastStatus,health:healthStatus}' --output json
} > "$OUT/deployment.txt"
aws_ logs tail "/ecs/mtp-$RUN" --since 30m --format short \
  | grep -E "Flyway|Successfully applied|Migrating schema|demo data|Started MetaPlatformApplication|ERROR" > "$OUT/app_log_excerpt.txt" || true
cat "$OUT/deployment.txt" "$OUT/app_log_excerpt.txt"
