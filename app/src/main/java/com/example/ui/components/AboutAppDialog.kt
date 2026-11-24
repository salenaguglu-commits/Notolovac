package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.R
import com.example.ui.theme.CoralPrimary
import com.example.ui.theme.CreamPaper
import com.example.ui.theme.HoneyGoldContainer
import com.example.ui.theme.HoneyGoldTertiary
import com.example.ui.theme.MutedInk
import com.example.ui.theme.OnSageMintContainer
import com.example.ui.theme.SageMintContainer
import com.example.ui.theme.SageMintSecondary
import com.example.ui.theme.WarmCardBorder
import com.example.ui.theme.WarmInk
import com.example.ui.theme.WarmSurface

/**
 * Storybook "About App" dialog revealed by long-pressing the kitten mascot icon in the header.
 * Features the watercolor piano-playing fox illustration, app version, zero-data/offline safety
 * guarantee, and invitation to visit bloom-it.rs/freebies or support Belgrade's homeless pets.
 */
@Composable
fun AboutAppDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val websiteUrl = stringResource(R.string.about_website_url)
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = CreamPaper,
            border = BorderStroke(1.5.dp, WarmCardBorder),
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 460.dp)
                .testTag("about_app_dialog")
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Watercolor Piano Fox Hero Illustration + Close Icon
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_notolovac_mascot_1795199060528),
                        contentDescription = stringResource(R.string.about_fox_image_desc),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(216.dp)
                            .clip(RoundedCornerShape(20.dp))
                    )

                    Surface(
                        shape = CircleShape,
                        color = CreamPaper.copy(alpha = 0.9f),
                        border = BorderStroke(1.dp, WarmCardBorder),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(48.dp)
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("about_close_icon_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_close),
                                tint = WarmInk
                            )
                        }
                    }
                }

                // 2. Title & Dynamic Version
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.about_title_format, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = WarmInk,
                        textAlign = TextAlign.Center
                    )
                }

                // 3. Free, Offline & Child-Safe Guarantee Card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = SageMintContainer.copy(alpha = 0.78f),
                    border = BorderStroke(1.dp, SageMintSecondary.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(SageMintSecondary.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = null,
                                tint = SageMintSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.about_privacy_safety_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSageMintContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 4. Website & Belgrade Homeless Cats & Dogs Donation Invitation Card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = HoneyGoldContainer.copy(alpha = 0.78f),
                    border = BorderStroke(1.dp, HoneyGoldTertiary.copy(alpha = 0.8f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(CoralPrimary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Pets,
                                    contentDescription = null,
                                    tint = CoralPrimary,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.about_community_donation_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = WarmInk,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Button(
                            onClick = {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl))
                                    context.startActivity(intent)
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CoralPrimary,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("about_open_website_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.about_website_cta),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // 5. Close Button
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.2.dp, WarmCardBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = WarmSurface,
                        contentColor = MutedInk
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("about_close_button")
                ) {
                    Text(
                        text = stringResource(R.string.action_close),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = WarmInk
                    )
                }
            }
        }
    }
}
