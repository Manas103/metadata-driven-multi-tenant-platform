# The service on AWS: one ECS Fargate task running the container image from ECR, an RDS
# PostgreSQL 16 instance only that task can reach, the database password generated here and
# handed to the task through SSM Parameter Store (never in the task definition), logs in
# CloudWatch. Everything sits in the account's default VPC; the task gets a public IP and its
# security group admits port 8080 from one address only (the operator's, var.allowed_cidr).
# scripts/aws_run.sh applies this, measures, and destroys it.

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.80" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "run_id" {
  type        = string
  description = "tags every resource, so a sweep after destroy can prove nothing is left"
}

variable "allowed_cidr" {
  type        = string
  description = "the one address allowed to reach the service, e.g. 203.0.113.7/32"
}

variable "image_tag" {
  type    = string
  default = "latest"
}

variable "desired_count" {
  type        = number
  default     = 1
  description = "0 for the first apply (ECR only has to exist), 1 once the image is pushed"
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { project = "metadata-driven-multi-tenant-platform", run = var.run_id }
  }
}

locals {
  name = "mtp-${var.run_id}"
}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

# ---- image ----------------------------------------------------------------

resource "aws_ecr_repository" "app" {
  name         = "metadata-driven-multi-tenant-platform"
  force_delete = true
}

# ---- network --------------------------------------------------------------

resource "aws_security_group" "task" {
  name        = "${local.name}-task"
  description = "service port from the operator only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "HTTP from the operator"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }

  egress {
    description = "image pull, SSM, logs, database"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "db" {
  name        = "${local.name}-db"
  description = "PostgreSQL from the service task only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "PostgreSQL from the task"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.task.id]
  }
}

# ---- database -------------------------------------------------------------

resource "random_password" "db" {
  length  = 32
  special = false
}

resource "aws_ssm_parameter" "db_password" {
  name  = "/${local.name}/db-password"
  type  = "SecureString"
  value = random_password.db.result
}

resource "aws_db_subnet_group" "db" {
  name       = local.name
  subnet_ids = data.aws_subnets.default.ids
}

resource "aws_db_instance" "db" {
  identifier              = local.name
  engine                  = "postgres"
  engine_version          = "16"
  instance_class          = "db.t4g.micro"
  allocated_storage       = 20
  storage_type            = "gp2"
  db_name                 = "metaplatform"
  username                = "metaplatform_app"
  password                = random_password.db.result
  db_subnet_group_name    = aws_db_subnet_group.db.name
  vpc_security_group_ids  = [aws_security_group.db.id]
  publicly_accessible     = false
  multi_az                = false
  backup_retention_period = 0
  skip_final_snapshot     = true
  deletion_protection     = false
  apply_immediately       = true
}

# ---- service --------------------------------------------------------------

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${local.name}"
  retention_in_days = 1
}

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${local.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "read_db_password" {
  statement {
    actions   = ["ssm:GetParameters"]
    resources = [aws_ssm_parameter.db_password.arn]
  }
}

resource "aws_iam_role_policy" "read_db_password" {
  name   = "read-db-password"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.read_db_password.json
}

resource "aws_ecs_cluster" "app" {
  name = local.name
}

resource "aws_ecs_task_definition" "app" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.execution.arn

  container_definitions = jsonencode([{
    name         = "app"
    image        = "${aws_ecr_repository.app.repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    environment = [
      { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.db.address}:5432/metaplatform" },
      { name = "SPRING_DATASOURCE_USERNAME", value = "metaplatform_app" },
      { name = "PLATFORM_DEMODATA", value = "true" },
    ]
    secrets = [{ name = "SPRING_DATASOURCE_PASSWORD", valueFrom = aws_ssm_parameter.db_password.arn }]
    healthCheck = {
      command     = ["CMD-SHELL", "bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1"]
      interval    = 10
      retries     = 6
      startPeriod = 120
      timeout     = 5
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "app"
      }
    }
  }])
}

resource "aws_ecs_service" "app" {
  name            = local.name
  cluster         = aws_ecs_cluster.app.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.task.id]
    assign_public_ip = true
  }
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "cluster" {
  value = aws_ecs_cluster.app.name
}

output "service" {
  value = aws_ecs_service.app.name
}

output "db_engine_version" {
  value = aws_db_instance.db.engine_version_actual
}
