---
name: branch-stack-reland
description: Put a change on every branch of a stack, or move work that was branched off the wrong level, by landing it at the bottom and merging up. Use when a change belongs to a lower branch than the one it was written on, when the same change has to reach several stacked branches, or when deciding whether a merged branch still needs testing.
---

# Working on a branch stack

A stack is a chain of branches where each one builds on the one below, so the lowest holds the
oldest work and the top holds all of it. At the time of writing:

```
kmp-aimi-migration-study            parent, not ours to change
  └── ..._DKA                       DKA protection
        └── ..._DanaRS_iOS          Dana driver for iOS
              └── ..._xdrip4ios_bgsource
```

Check the real shape before trusting any drawing: `git merge-base A B` and
`git log --oneline B..A` say what is really on top of what.

## The rule

**Land a change at the lowest branch that owns it, then merge up.** A change to the DKA code goes on
`_DKA`, then `_DKA` merges into `_DanaRS_iOS`, then that merges into the next.

**Never cherry-pick the same change onto each branch of a stack.** It looks faster and it leaves
three copies with different commit ids. Git then sees them as unrelated work, the next merge
conflicts on every line of it, and resolving that conflict is where a change quietly loses half of
itself.

Work written on the wrong branch is re-landed, not moved by hand:

```
git checkout -B reland origin/<the branch that owns it>
git cherry-pick <first>..<last>        # or list them
# merge up, one branch at a time
git checkout -B up1 origin/<next branch>   && git merge --no-edit reland
git checkout -B up2 origin/<branch above>  && git merge --no-edit up1
git push origin reland:<owner> up1:<next> up2:<above>
```

Cherry-picking here is right because the commits are being re-homed, not copied: the branch they came
from is deleted afterwards, so no second copy survives.

## Proving a merge without testing it again

A git tree object is the hash of the whole file tree. Two commits with the same tree hold byte for
byte the same code, whatever their history.

```
git rev-parse <merged branch>^{tree}
git rev-parse <the commit you already tested>^{tree}
```

**Equal hashes mean the merged branch needs no retesting.** This is the payoff for the discipline
below, and it turns "rebuild and retest the whole tip" into one command.

It says nothing about the branches *under* the tip. Their trees are new and untested, and a lower
branch is the more likely place to break, because it has less around it: a call that resolves on the
tip can be missing there. Test the lowest branch first.

## Keep files byte-identical across the stack

When the same file has to change on more than one branch, make it **exactly** the same file, not an
equivalent one. A merge of two identical files is a no-op; a merge of two files that differ only in
whitespace or import order is a conflict. This is what makes the tree check above work at all, and
it is worth copying a whole file from the other branch rather than retyping the change.

## Traps

- **A shallow clone has no ancestry.** `git merge-base` and `--contains` lie or fail. Fetch what you
  need with a depth and an explicit ref:
  `git fetch --depth=200 origin <branch>:refs/remotes/origin/<branch>`.
- **A single-branch `remote.origin.fetch`** means a push never updates the other branches' tracking
  refs. `origin/<branch>` then still points at where that branch was before the last push, so tools
  report commits as unpushed when they are not, and worse, a new branch cut from `origin/<branch>`
  silently starts from the old code and would revert whatever was pushed since. Refresh the ref
  before branching from it, not only before reporting on it:
  `git fetch --depth=200 origin <branch>:refs/remotes/origin/<branch>`. Check the truth with
  `git ls-remote`, never the local ref. Do not change the git config to fix it; tell the user.
- **Deleting a remote branch can fail** where pushing works (`send-pack: unexpected disconnect`).
  Retry once, then ask the user to delete it from their own machine.
- **A branch outside the stack is a port, not a cherry-pick.** Pre-KMP branches keep the same classes
  under `src/main/kotlin` with `R.string` ids and a different settings wiring, so the patch will not
  apply. Read the target's own files first and write the change in its spelling.
- **Check whether a failing test was already failing** before fixing it: `git show <base>:<path>`.
  A test that contradicts the code it tests is often older than the change being blamed.
