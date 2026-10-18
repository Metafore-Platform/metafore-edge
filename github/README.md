# GitHub settings for `development` (Compass a3990b6e)

`development.yml` tests the branch, builds the image, and on a push publishes `metafore-dev/edge:<sha>` to ECR and (when
asked) pins its digest in `Metafore-Platform/metafore-deploy`. The settings below are what that needs; an admin applies them
once. Nothing publishes until step 3 sets `EDGE_PUBLISH_ENABLED`, and nothing is pinned until `EDGE_PIN_ENABLED` is set.

1. **Role** (metafore-infra `terraform/dev`): `github-actions-metafore-dev-edge-publish`, trusting only
   `repo:Metafore-Platform/metafore-edge:environment:development` (and the org-id form), with ECR push on the repository
   `metafore-dev/edge` and nothing else.
2. **Environment** `development` on this repository, deployable from the `development` branch only:

   ```
   gh api -X PUT repos/Metafore-Platform/metafore-edge/environments/development --input - <<'JSON'
   {"deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}}
   JSON
   gh api -X POST repos/Metafore-Platform/metafore-edge/environments/development/deployment-branch-policies -f name=development
   ```
3. **Variables and secret.** Repository variables (metafore-infra #49 made the role; its ARN is the output `edge_publish_role_arn`):

   ```
   gh variable set EDGE_PUBLISH_ROLE_ARN --repo Metafore-Platform/metafore-edge --body arn:aws:iam::359469026479:role/github-actions-metafore-dev-edge-publish
   gh variable set EDGE_PUBLISH_ENABLED  --repo Metafore-Platform/metafore-edge --body true
   ```

   The pin (later, with the broker sign-in, Compass f2ad23ca) needs the GitHub App `metafore-dev-deploy` (id 5088348):

   ```
   gh variable set DEPLOY_APP_ID --repo Metafore-Platform/metafore-edge --body 5088348
   gh secret set DEPLOY_APP_PRIVATE_KEY --env development --repo Metafore-Platform/metafore-edge < path/to/the-app-private-key.pem
   gh variable set EDGE_PIN_ENABLED --repo Metafore-Platform/metafore-edge --body true
   ```

   The key is an **environment** secret, not the organisation's `DEPLOY_APP_PRIVATE_KEY`: this repository is public and a workflow on any
   branch of it can read an organisation or repository secret, while an environment secret goes only to jobs running in the
   `development` environment, which accepts the `development` branch only (step 2). Do not add this repository to the
   organisation secret's selected repositories.
4. **Ruleset**: the required checks of `development` (`github/development-ruleset.json`):

   ```
   gh api -X POST repos/Metafore-Platform/metafore-edge/rulesets --input github/development-ruleset.json
   ```

`scripts/check_no_deploy_on_development.py` (the `guard` job) keeps all of this narrow: the only job that may hold credentials
is `publish`, on a push to development, after every other job, in the `development` environment, to `metafore-dev/edge`.
