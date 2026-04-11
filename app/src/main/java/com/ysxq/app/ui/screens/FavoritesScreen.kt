package com.ysxq.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ysxq.app.data.local.FavoriteItem
import com.ysxq.app.data.local.favoritesStore
import com.ysxq.app.data.sync.FavoritesSyncRepository
import com.ysxq.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onVideoClick: (Int) -> Unit
) {
    val context = LocalContext.current
    val favoritesStore = remember { context.favoritesStore() }
    val favoritesSyncRepo = remember { FavoritesSyncRepository(favoritesStore) }
    val favorites by favoritesStore.favorites.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        favoritesSyncRepo.pullFromCloud()
    }

    Column(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        TopAppBar(
            title = { Text("我的收藏", color = TextPrimary, fontSize = 18.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
        )

        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("暂无收藏", color = TextTertiary, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("在影片详情页点击收藏即可添加", color = TextTertiary, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(favorites, key = { it.id }) { item ->
                    FavoriteItemRow(
                        item = item,
                        onVideoClick = { onVideoClick(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteItemRow(
    item: FavoriteItem,
    onVideoClick: () -> Unit
) {
    val context = LocalContext.current
    val favoritesStore = remember { context.favoritesStore() }
    val favoritesSyncRepo = remember { FavoritesSyncRepository(favoritesStore) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onVideoClick),
        color = DarkSurface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = item.pic,
                contentDescription = item.name,
                modifier = Modifier
                    .size(width = 100.dp, height = 65.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (item.typeName.isNotBlank()) {
                        Text(item.typeName, color = SakuraPrimary, fontSize = 12.sp)
                    }
                    if (item.remarks.isNotBlank()) {
                        Text(item.remarks, color = SkyBlue, fontSize = 12.sp)
                    }
                }
            }

            IconButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    favoritesStore.removeFavorite(item.id)
                    favoritesSyncRepo.deleteFromCloud(item.id)
                }
            }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "移除",
                    tint = TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
