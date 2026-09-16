# Navigation regression evidence

These three manual/metadata pairs are the user's actual 20260906-155748 exports
from docs/logs/living-manual-v2-navigation-*. The accepted c-before pair predates
the failure; c-after and d-clock are semantically damaged despite valid SHA pairs.
Tests normalize checkout line endings before checking the original Markdown SHA.

Observation objects and gateway responses in ManualNavigationRegressionTest are
explicit reconstructions/mocks, NOT captured model responses or original UI trees.
They reconstruct the failure shape shown by the screenshots, logs and exports.
No network, phone interaction, user contacts/messages or credentials are included.
