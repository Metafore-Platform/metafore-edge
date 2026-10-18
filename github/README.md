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
3. **Variables** (repository): `EDGE_PUBLISH_ROLE_ARN` (the role of step 1), `EDGE_PUBLISH_ENABLED=true`. For the pin, later:
   `DEPLOY_APP_ID` and `EDGE_PIN_ENABLED=true`, and the **environment secret** `DEPLOY_APP_PRIVATE_KEY` (GitHub App
   `metafore-dev-deploy`, installed on `metafore-deploy`, the same key core and web use).
4. **Ruleset**: the required checks of `development` (`github/development-ruleset.json`):

   ```
   gh api -X POST repos/Metafore-Platform/metafore-edge/rulesets --input github/development-ruleset.json
   ```

`scripts/check_no_deploy_on_development.py` (the `guard` job) keeps all of this narrow: the only job that may hold credentials
is `publish`, on a push to development, after every other job, in the `development` environment, to `metafore-dev/edge`.
