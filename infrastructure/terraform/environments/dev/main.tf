terraform {
  required_version = ">= 1.7"

  required_providers {
    aws        = { source = "hashicorp/aws",       version = "~> 5.0"  }
    kubernetes = { source = "hashicorp/kubernetes", version = "~> 2.30" }
    helm       = { source = "hashicorp/helm",       version = "~> 2.14" }
    random     = { source = "hashicorp/random",     version = "~> 3.6"  }
  }

  backend "s3" {
    bucket         = "sssm-terraform-state"
    key            = "dev/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "sssm-terraform-locks"
    profile        = "sssm-prod"
  }
}

provider "aws" {
  region  = var.aws_region
  profile = "sssm-dev"

  default_tags {
    tags = {
      Project     = var.project
      Environment = var.env
      ManagedBy   = "terraform"
    }
  }
}

module "vpc" {
  source               = "../../modules/vpc"
  project              = var.project
  env                  = var.env
  vpc_cidr             = var.vpc_cidr
  availability_zones   = var.availability_zones
  public_subnets       = var.public_subnets
  private_subnets      = var.private_subnets
  database_subnets     = var.database_subnets
}

module "eks" {
  source              = "../../modules/eks"
  project             = var.project
  env                 = var.env
  eks_cluster_version = var.eks_cluster_version
  vpc_id              = module.vpc.vpc_id
  private_subnet_ids  = module.vpc.private_subnet_ids
}

module "rds" {
  source              = "../../modules/rds"
  project             = var.project
  env                 = var.env
  vpc_id              = module.vpc.vpc_id
  vpc_cidr            = var.vpc_cidr
  database_subnet_ids = module.vpc.database_subnet_ids
  availability_zones  = var.availability_zones
  rds_instance_class  = var.rds_instance_class
}

module "elasticache" {
  source              = "../../modules/elasticache"
  project             = var.project
  env                 = var.env
  vpc_id              = module.vpc.vpc_id
  vpc_cidr            = var.vpc_cidr
  database_subnet_ids = module.vpc.database_subnet_ids
  redis_node_type     = var.redis_node_type
}

module "msk" {
  source              = "../../modules/msk"
  project             = var.project
  env                 = var.env
  vpc_id              = module.vpc.vpc_id
  vpc_cidr            = var.vpc_cidr
  database_subnet_ids = module.vpc.database_subnet_ids
  kafka_instance_type = var.kafka_instance_type
  kafka_storage_gb    = var.kafka_storage_gb
}

module "opensearch" {
  source                  = "../../modules/opensearch"
  project                 = var.project
  env                     = var.env
  vpc_id                  = module.vpc.vpc_id
  vpc_cidr                = var.vpc_cidr
  database_subnet_ids     = module.vpc.database_subnet_ids
  oidc_provider_arn       = module.eks.oidc_provider_arn
  opensearch_instance_type = var.opensearch_instance_type
  opensearch_volume_gb    = var.opensearch_volume_gb
}

module "s3" {
  source  = "../../modules/s3"
  project = var.project
  env     = var.env
}

module "ecr" {
  source     = "../../modules/ecr"
  project    = var.project
  env        = var.env
  aws_region = var.aws_region
}

module "iam" {
  source           = "../../modules/iam"
  project          = var.project
  env              = var.env
  oidc_provider_arn = module.eks.oidc_provider_arn
}

module "dns" {
  source      = "../../modules/dns"
  project     = var.project
  env         = var.env
  domain_name = var.domain_name
}
