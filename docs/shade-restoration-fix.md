# Independent restoration of extra status bars

Notification/QS header reinflation can replace the icon area while DuoIconHost still keeps the old named slot. Re-resolve the header area before treating an existing attachment as complete; release the old renderer and restore its stock icons before attaching to the replacement area.

Each extra bar now owns its StockIconHider state. Arrival hooks, ready callbacks and hiding reapplication use that owner. Failure, replacement, teardown and hiding-mode changes restore the corresponding stock state. The main bar keeps its own owner. CombinedStatusView is recognized as a replaced cluster when there is no readable icon slot, consistent with the existing One UI main-bar anchor.

No polling, new hook names, package changes, updater changes or new Rive asset were introduced. Missing icon areas leave the stock header alone. This is a focused upstream subset of work in the DUO Recreate fork; independent panel sizes and fork UI are not part of this proposal.

Validation: automated Robolectric restoration checks plus the existing suite. Physical Samsung One UI header transitions remain unverified; compare a device diagnostic dump before/after before treating this proposal as ready for a release. Rive source and duo.riv remain byte-identical to the upstream base; no native renderer modifications.
