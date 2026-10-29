# Infrastructure (US-003)

OpenTofu definitions for the staging and production environments (ADR-006, ADR-012).

| Path | What it creates |
| --- | --- |
| `modules/edge` | Cloudflare R2 bucket for audio and proxied DNS records (CDN, WAF, DDoS protection) |
| `modules/app-host` | One Oracle Cloud always-free ARM VM with Docker, in its own network. HTTPS is open only to Cloudflare's edge ranges; SSH only to `admin_cidrs` |
| `envs/staging`, `envs/production` | The two environments. Staging gets 1 OCPU and 6 GB, production 3 OCPU and 18 GB, together the always-free ARM allowance [A-030; check C-03 before applying] |

## Honest limits

- **Production is one VM until funding.** That is not highly available: if the VM fails, the site is down until it is rebuilt from these definitions. The move to Kubernetes across zones after funding (ADR-006) is what makes it highly available; the same images and modules carry over.
- GPU workers are not defined here. They run on startup credits or a GPU marketplace with scale-to-zero, set up once the Sprint 0 benchmark (US-005) picks the GPU type.
- Nothing has been applied. It needs the accounts below first.

## One-time setup (founder)

1. Create a Cloudflare account, add the product domain, and create an API token limited to that zone's DNS and to R2.
2. Create an Oracle Cloud account in the Mumbai region (`ap-mumbai-1`), and an API key for a deploy user.
3. In Cloudflare R2, create a private bucket `beatmind-tofu-state` and an R2 access key for it.
4. In GitHub, create environments `staging` and `production` (production with a required reviewer), and add these secrets to each:

| Secret | From |
| --- | --- |
| `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_ZONE_ID` | Cloudflare dashboard |
| `OCI_TENANCY_OCID`, `OCI_USER_OCID`, `OCI_FINGERPRINT`, `OCI_PRIVATE_KEY`, `OCI_COMPARTMENT_OCID`, `OCI_IMAGE_OCID` | Oracle Cloud console (Ubuntu aarch64 image) |
| `SSH_PUBLIC_KEY`, `ADMIN_CIDRS` (JSON list, for example `["203.0.113.4/32"]`) | Your machine |
| `STATE_BUCKET_ACCESS_KEY`, `STATE_BUCKET_SECRET_KEY`, `STATE_BUCKET_ENDPOINT` | R2 access key; endpoint `https://<account-id>.r2.cloudflarestorage.com` |

Then run the **Infrastructure** workflow from the Actions tab: `plan` first, read it, then `apply`.

## Local checks

```sh
tofu fmt -recursive -check
cd envs/staging && tofu init -backend=false && tofu validate
```
