// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

use ruopus::ogg::{ChannelMapping, OggOpusReader, OpusHead, OpusTags, PageWriter};

const SAMPLE_RATE: u64 = 48_000;
const MAX_FRAMES: u64 = SAMPLE_RATE * 60 * 10;
const MAX_ENCODED_BYTES: usize = 16 * 1024 * 1024;

#[derive(uniffi::Record)]
pub struct WindowsVoicePcm {
    pub pcm: Vec<u8>,
    pub channels: u8,
}

#[uniffi::export]
pub fn windows_decode_voice(ogg: Vec<u8>) -> Option<WindowsVoicePcm> {
    if ogg.is_empty() || ogg.len() > MAX_ENCODED_BYTES {
        return None;
    }
    let mut scan = OggOpusReader::new(&ogg).ok()?;
    let channels = scan.head().channel_count;
    if !matches!(channels, 1 | 2) || !matches!(&scan.head().channel_mapping, ChannelMapping::Family0)
    {
        return None;
    }
    // Bound the decoded allocation by packet duration as well as by encoded bytes. A small Ogg
    // file can contain many short, highly compressed packets. Granule positions are sender data.
    let mut frames = 0;
    while let Some(packet) = scan.next() {
        let duration = ruopus::Packet::parse(&packet.data).ok()?.duration();
        frames += (duration.as_nanos() * u128::from(SAMPLE_RATE) / 1_000_000_000) as u64;
        if frames > MAX_FRAMES + 5_760 {
            return None;
        }
    }
    if frames == 0 {
        return None;
    }
    let (samples, _) = ruopus::decode_ogg_opus(&ogg).ok()?;
    if samples.is_empty() || samples.len() > MAX_FRAMES as usize * usize::from(channels) {
        return None;
    }
    let pcm = samples
        .into_iter()
        .flat_map(|sample| ((sample * 32768.0).round() as i16).to_le_bytes())
        .collect();
    Some(WindowsVoicePcm { pcm, channels })
}

#[uniffi::export]
pub fn windows_encode_voice(pcm: Vec<u8>) -> Option<Vec<u8>> {
    if pcm.len() < 2 || pcm.len() % 2 != 0 || pcm.len() as u64 / 2 > MAX_FRAMES {
        return None;
    }
    const FRAME: usize = 960;
    // The codec's 24 kbit/s mono hybrid mode has 69 samples of encoder delay.
    const PRE_SKIP: u16 = 69;
    let frame_count = pcm.len() / 2;
    let trimmed_granule = frame_count + usize::from(PRE_SKIP);
    let packet_count = trimmed_granule.div_ceil(FRAME);
    let head = OpusHead::family0(1, PRE_SKIP, SAMPLE_RATE as u32);
    let tags = OpusTags { vendor: b"Bipe".to_vec(), comments: Vec::new() };
    let mut writer = PageWriter::new(1);
    let mut output = Vec::new();
    writer.push(&mut output, &head.to_bytes(), 0, false);
    writer.flush(&mut output);
    writer.push(&mut output, &tags.to_bytes(), 0, false);
    writer.flush(&mut output);

    let mut encoder = ruopus::OpusEncoder::new(1);
    encoder.set_bandwidth(ruopus::Bandwidth::FullBand);
    encoder.set_bitrate(Some(24_000));
    let mut samples = pcm.chunks_exact(2);
    for number in 1..=packet_count {
        let mut frame = [0.0; FRAME];
        for sample in &mut frame {
            let Some(bytes) = samples.next() else { break };
            *sample = f32::from(i16::from_le_bytes([bytes[0], bytes[1]])) / 32768.0;
        }
        let packet = encoder.encode_auto(&frame, 1275).ok()?;
        writer.push(&mut output, &packet, (number * FRAME).min(trimmed_granule) as u64,
            number == packet_count);
    }
    Some(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn windows_recording_round_trips_as_ogg_opus() {
        let pcm = vec![0u8; 4_800 * 2];
        let ogg = windows_encode_voice(pcm).unwrap();
        assert!(ogg.starts_with(b"OggS"));
        let decoded = windows_decode_voice(ogg).unwrap();
        assert_eq!(decoded.channels, 1);
        assert_eq!(decoded.pcm.len(), 4_800 * 2);
        assert!(windows_decode_voice(b"not an Ogg file".to_vec()).is_none());
    }
}
