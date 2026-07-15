variable "prefix" {
  type    = string
  default = "credithub"
}

variable "location" {
  type    = string
  default = "brazilsouth"
}

variable "postgres_admin_password" {
  type      = string
  sensitive = true
}
