package com.golda.patchertiktok;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DownloadModelsTest {
    @Test public void bestQualityUsesResolutionThenBitrateRatherThanServerListOrder() {
        Post post = new Post();
        post.video.rates = Arrays.asList(new Rate("720", 1280, 720, 3_000_000),
                new Rate("1080-low", 1080, 1920, 2_000_000), new Rate("1080-high", 1920, 1080, 4_000_000));
        List<DownloadModels.Item> choices = DownloadModels.videos(post);
        assertEquals("https://cdn.example/1080-high", choices.get(0).urls.get(0));
        assertEquals("https://cdn.example/1080-low", choices.get(1).urls.get(0));
        assertEquals("https://cdn.example/720", choices.get(2).urls.get(0));
        assertEquals("1080p / ~15.0 MB", choices.get(0).label());
    }

    @Test public void baseCodecDuplicateDoesNotAddAnotherQualityRow() {
        Post post = new Post();
        Rate rate = new Rate("video", 1920, 1080, 4_000_000);
        rate.address.size = 4_200_000;
        post.video.rates = Collections.singletonList(rate);
        post.video.address = rate.address;
        List<DownloadModels.Item> choices = DownloadModels.videos(post);
        assertEquals(1, choices.size());
        assertEquals("1080p / 4.2 MB", choices.get(0).label());
    }

    @Test public void carouselKeepsOriginalUrlsEvenWhenAnAdaptiveGetterReturnsAThumbnail() {
        List<DownloadModels.Item> photos = DownloadModels.photos(Arrays.asList(new Address("photo1"), new Address("photo2")));
        assertEquals(2, photos.size());
        assertEquals("https://cdn.example/photo1", photos.get(0).urls.get(0));
        assertEquals("https://cdn.example/photo2", photos.get(1).urls.get(0));
        assertEquals(DownloadFormat.Kind.PHOTO, photos.get(0).kind);
    }

    @Test public void incompleteCarouselIsLeftToTikTokInsteadOfSilentlySavingOnlySomeImages() {
        Address absent = new Address("absent");
        absent.urlList = Collections.singletonList("file:///not-a-cdn-image");
        assertTrue(DownloadModels.photos(Arrays.asList(new Address("first"), absent)).isEmpty());
    }

    @Test public void audioUsesTheMusicAddressAndAbsentMusicDoesNotInventAnOption() {
        Post post = new Post();
        assertNull(DownloadModels.audio(post));
        post.music = new Music();
        DownloadModels.Item audio = DownloadModels.audio(post);
        assertEquals(DownloadFormat.Kind.AUDIO, audio.kind);
        assertEquals("https://cdn.example/sound", audio.urls.get(0));
    }

    public static final class Post {
        final Video video = new Video();
        Music music;
        public Video getVideo() { return video; }
        public Music getMusic() { return music; }
    }

    public static final class Video {
        List<Rate> rates = Collections.emptyList();
        Address address;
        public List<Rate> getRawBitRate() { return rates; }
        public Address getPlayAddr() { return address; }
        public Address getPlayAddrH264() { return address; }
        public int getWidth() { return 1920; }
        public int getHeight() { return 1080; }
        public int getDuration() { return 30_000; }
    }

    public static final class Rate {
        final Address address;
        final int width;
        final int height;
        final int bitrate;
        Rate(String name, int width, int height, int bitrate) {
            this.address = new Address(name);
            this.width = width;
            this.height = height;
            this.bitrate = bitrate;
        }
        public Address getPlayAddr() { return address; }
        public int getVideoWidth() { return width; }
        public int getVideoHeight() { return height; }
        public int getBitRate() { return bitrate; }
        public int getSize() { return 0; }
    }

    public static final class Address {
        List<String> urlList;
        long size;
        Address(String name) { urlList = Collections.singletonList("https://cdn.example/" + name); }
        public long getSize() { return size; }
        public List<String> getUrlList() { return Collections.singletonList("https://cdn.example/thumbnail"); }
    }

    public static final class Music {
        public Address getPlayUrl() { return new Address("sound"); }
    }
}
