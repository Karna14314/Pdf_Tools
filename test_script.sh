#!/bin/bash
# Recomposition / performance optimization in PdfViewerScreen.kt

# Currently, inside the items(count = totalPages) block of LazyColumn, there are computationally expensive filtering operations that get run on every recomposition.
# Specifically:
# val pageMatches = searchState.matches.filter { it.pageIndex == index }
# annotations.filter { it.pageIndex == index }

# According to memory rule: "In Compose UI, computationally expensive operations within LazyColumn items, such as filtering searchState.matches or annotations, must be wrapped in remember blocks to prevent excessive recompositions and allocations during scrolling."
