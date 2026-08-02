#!/usr/bin/env python3
"""Calibrate Dossier's pinned YuNet/SFace face-correlation pipeline.

The input manifest must contain consented images and identity-disjoint
``calibration`` and ``test`` splits. No face dataset is bundled with Dossier.

Required CSV columns:
    left,right,left_identity,right_identity,same_person,split

Optional columns:
    demographic_group,device_class

Example:
    python tools/face_calibration.py \
      --manifest private/face_pairs.csv \
      --root private/images \
      --yunet face_detection_yunet_2023mar.onnx \
      --sface face_recognition_sface_2021dec.onnx \
      --output face-correlation-calibration.json

The emitted JSON is accepted by FaceCorrelationCalibrationStore only when both
model hashes and the preprocessing pipeline version match Android exactly.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import random
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

import cv2
import numpy as np

PIPELINE_VERSION = "yunet-2023mar+sface-2021dec-aligncrop-v1"
PINNED_YUNET_SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
PINNED_SFACE_SHA256 = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
YUNET_SIZE_BYTES = 232_589
SFACE_SIZE_BYTES = 38_696_353

DETECTION_SCORE_THRESHOLD = 0.80
MIN_ACCEPTED_DETECTOR_SCORE = 0.82
NMS_THRESHOLD = 0.30
TOP_K = 5_000
MIN_FACE_DIMENSION = 72.0
MIN_FACE_AREA_RATIO = 0.012
MIN_EYE_DISTANCE = 20.0
MAX_ABS_ROLL_DEGREES = 28.0
MIN_BRIGHTNESS = 28.0
MAX_BRIGHTNESS = 232.0
MIN_LAPLACIAN_VARIANCE = 18.0
AMBIGUOUS_AREA_RATIO = 0.58
AMBIGUOUS_SCORE_DELTA = 0.08
MAX_DECODE_DIMENSION = 1_600


@dataclass(frozen=True)
class Pair:
    left: Path
    right: Path
    left_identity: str
    right_identity: str
    same_person: bool
    split: str
    demographic_group: str
    device_class: str


@dataclass(frozen=True)
class PreparedFace:
    feature: np.ndarray
    quality: dict[str, float]


@dataclass(frozen=True)
class ScoredPair:
    pair: Pair
    score: float


class FacePreparationError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--yunet", required=True, type=Path)
    parser.add_argument("--sface", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--review-max-fmr", type=float, default=0.01)
    parser.add_argument("--high-max-fmr", type=float, default=0.0001)
    parser.add_argument("--minimum-calibration-positive", type=int, default=500)
    parser.add_argument("--minimum-calibration-negative", type=int, default=10_000)
    parser.add_argument("--minimum-test-positive", type=int, default=500)
    parser.add_argument("--minimum-test-negative", type=int, default=10_000)
    parser.add_argument("--bootstrap-samples", type=int, default=2_000)
    parser.add_argument("--seed", type=int, default=20260803)
    parser.add_argument("--allow-unpinned-models", action="store_true")
    parser.add_argument(
        "--max-rejection-rate",
        type=float,
        default=0.25,
        help="Abort when image-quality rejection exceeds this fraction.",
    )
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_model(
    path: Path,
    expected_sha: str,
    expected_size: int,
    allow_unpinned: bool,
) -> str:
    if not path.is_file():
        raise SystemExit(f"Model not found: {path}")
    actual_sha = sha256(path)
    if not allow_unpinned:
        if path.stat().st_size != expected_size:
            raise SystemExit(
                f"Pinned model size mismatch for {path}: "
                f"expected {expected_size}, got {path.stat().st_size}"
            )
        if actual_sha.lower() != expected_sha:
            raise SystemExit(
                f"Pinned model SHA-256 mismatch for {path}: "
                f"expected {expected_sha}, got {actual_sha}"
            )
    return actual_sha.lower()


def parse_bool(value: str) -> bool:
    normalized = value.strip().lower()
    if normalized in {"1", "true", "yes", "same", "positive"}:
        return True
    if normalized in {"0", "false", "no", "different", "negative"}:
        return False
    raise ValueError(f"Invalid same_person value: {value!r}")


def load_manifest(path: Path, root: Path) -> list[Pair]:
    required = {
        "left",
        "right",
        "left_identity",
        "right_identity",
        "same_person",
        "split",
    }
    pairs: list[Pair] = []
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        missing = required - set(reader.fieldnames or [])
        if missing:
            raise SystemExit(f"Manifest missing columns: {', '.join(sorted(missing))}")
        for line_number, row in enumerate(reader, start=2):
            try:
                left = (root / row["left"].strip()).resolve()
                right = (root / row["right"].strip()).resolve()
                left_identity = row["left_identity"].strip()
                right_identity = row["right_identity"].strip()
                same_person = parse_bool(row["same_person"])
                split = row["split"].strip().lower()
                if split not in {"calibration", "test"}:
                    raise ValueError("split must be calibration or test")
                if not left_identity or not right_identity:
                    raise ValueError("identity IDs cannot be blank")
                if same_person and left_identity != right_identity:
                    raise ValueError("positive pair identity IDs must match")
                if not same_person and left_identity == right_identity:
                    raise ValueError("negative pair identity IDs must differ")
                if not left.is_file() or not right.is_file():
                    raise ValueError("one or both image files do not exist")
                pairs.append(
                    Pair(
                        left=left,
                        right=right,
                        left_identity=left_identity,
                        right_identity=right_identity,
                        same_person=same_person,
                        split=split,
                        demographic_group=(
                            row.get("demographic_group", "unknown").strip()
                            or "unknown"
                        ),
                        device_class=(
                            row.get("device_class", "unknown").strip()
                            or "unknown"
                        ),
                    )
                )
            except ValueError as error:
                raise SystemExit(f"Manifest line {line_number}: {error}") from error
    if not pairs:
        raise SystemExit("Manifest is empty")
    return pairs


def assert_identity_disjoint(pairs: Sequence[Pair]) -> None:
    identities: dict[str, set[str]] = defaultdict(set)
    for pair in pairs:
        identities[pair.split].update((pair.left_identity, pair.right_identity))
    overlap = identities["calibration"] & identities["test"]
    if overlap:
        sample = ", ".join(sorted(overlap)[:10])
        raise SystemExit(
            "Calibration and test identities overlap. Splits must be "
            f"identity-disjoint. Examples: {sample}"
        )


def bounded_read(path: Path) -> np.ndarray:
    # Modern OpenCV applies EXIF orientation unless IMREAD_IGNORE_ORIENTATION is
    # requested. This mirrors Android's explicit ExifInterface correction.
    image = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if image is None or image.size == 0:
        raise FacePreparationError("image could not be decoded")
    height, width = image.shape[:2]
    scale = min(1.0, MAX_DECODE_DIMENSION / max(width, height))
    if scale < 1.0:
        image = cv2.resize(
            image,
            (max(1, round(width * scale)), max(1, round(height * scale))),
            interpolation=cv2.INTER_AREA,
        )
    return image


def select_face(faces: np.ndarray) -> np.ndarray:
    if faces is None or len(faces) == 0:
        raise FacePreparationError("no face detected")
    candidates: list[tuple[float, np.ndarray]] = []
    for face in np.asarray(faces):
        if face.shape[0] < 15:
            continue
        width, height, score = float(face[2]), float(face[3]), float(face[14])
        if width <= 0 or height <= 0 or score < MIN_ACCEPTED_DETECTOR_SCORE:
            continue
        candidates.append((width * height * score, face))
    if not candidates:
        raise FacePreparationError("no face passed detector confidence")
    candidates.sort(key=lambda item: item[0], reverse=True)
    first = candidates[0][1]
    if len(candidates) > 1:
        second = candidates[1][1]
        first_area = float(first[2] * first[3])
        second_area = float(second[2] * second[3])
        if (
            second_area >= first_area * AMBIGUOUS_AREA_RATIO
            and float(second[14]) >= float(first[14]) - AMBIGUOUS_SCORE_DELTA
        ):
            raise FacePreparationError("multiple similarly prominent faces")
    return first.reshape(1, -1)


def quality_metrics(
    face: np.ndarray,
    aligned: np.ndarray,
    image: np.ndarray,
) -> dict[str, float]:
    values = face.reshape(-1)
    width, height = float(values[2]), float(values[3])
    detector_score = float(values[14])
    right_eye = values[4:6].astype(np.float64)
    left_eye = values[6:8].astype(np.float64)
    eye_delta = left_eye - right_eye
    eye_distance = float(np.linalg.norm(eye_delta))
    roll = float(
        math.degrees(math.atan2(float(eye_delta[1]), float(eye_delta[0])))
    )
    image_height, image_width = image.shape[:2]
    area_ratio = width * height / float(image_width * image_height)
    gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)
    brightness = float(gray.mean())
    laplacian_variance = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    metrics = {
        "detectorScore": detector_score,
        "faceWidth": width,
        "faceHeight": height,
        "faceAreaRatio": area_ratio,
        "eyeDistance": eye_distance,
        "rollDegrees": roll,
        "brightness": brightness,
        "laplacianVariance": laplacian_variance,
    }
    rejection = None
    if detector_score < MIN_ACCEPTED_DETECTOR_SCORE:
        rejection = "low detector confidence"
    elif width < MIN_FACE_DIMENSION or height < MIN_FACE_DIMENSION:
        rejection = "face too small"
    elif area_ratio < MIN_FACE_AREA_RATIO:
        rejection = "face occupies too little of image"
    elif eye_distance < MIN_EYE_DISTANCE:
        rejection = "landmarks too close"
    elif abs(roll) > MAX_ABS_ROLL_DEGREES:
        rejection = "excessive roll"
    elif not MIN_BRIGHTNESS <= brightness <= MAX_BRIGHTNESS:
        rejection = "exposure outside accepted range"
    elif laplacian_variance < MIN_LAPLACIAN_VARIANCE:
        rejection = "blur/compression below quality threshold"
    if rejection:
        raise FacePreparationError(rejection)
    return metrics


class Pipeline:
    def __init__(self, yunet: Path, sface: Path) -> None:
        self.detector = cv2.FaceDetectorYN.create(
            str(yunet),
            "",
            (320, 320),
            DETECTION_SCORE_THRESHOLD,
            NMS_THRESHOLD,
            TOP_K,
        )
        self.recognizer = cv2.FaceRecognizerSF.create(str(sface), "")
        self.cache: dict[Path, PreparedFace | FacePreparationError] = {}

    def prepare(self, path: Path) -> PreparedFace:
        cached = self.cache.get(path)
        if isinstance(cached, FacePreparationError):
            raise cached
        if isinstance(cached, PreparedFace):
            return cached
        try:
            image = bounded_read(path)
            self.detector.setInputSize((image.shape[1], image.shape[0]))
            _, faces = self.detector.detect(image)
            face = select_face(faces)
            aligned = self.recognizer.alignCrop(image, face)
            if aligned is None or aligned.size == 0:
                raise FacePreparationError("five-landmark alignment failed")
            quality = quality_metrics(face, aligned, image)
            feature = self.recognizer.feature(aligned)
            if feature is None or feature.size == 0:
                raise FacePreparationError("SFace embedding failed")
            prepared = PreparedFace(
                feature=np.asarray(feature).copy(),
                quality=quality,
            )
            self.cache[path] = prepared
            return prepared
        except FacePreparationError as error:
            self.cache[path] = error
            raise

    def score(self, pair: Pair) -> float:
        left = self.prepare(pair.left)
        right = self.prepare(pair.right)
        value = self.recognizer.match(
            left.feature,
            right.feature,
            cv2.FaceRecognizerSF_FR_COSINE,
        )
        return float(np.clip(value, -1.0, 1.0))


def score_pairs(
    pipeline: Pipeline,
    pairs: Sequence[Pair],
) -> tuple[list[ScoredPair], dict[str, int]]:
    scored: list[ScoredPair] = []
    rejections: dict[str, int] = defaultdict(int)
    for index, pair in enumerate(pairs, start=1):
        try:
            scored.append(ScoredPair(pair=pair, score=pipeline.score(pair)))
        except FacePreparationError as error:
            rejections[str(error)] += 1
        if index % 100 == 0 or index == len(pairs):
            print(
                f"Scored {index}/{len(pairs)} pairs; accepted {len(scored)}",
                file=sys.stderr,
            )
    return scored, dict(sorted(rejections.items()))


def rate(scores: Sequence[float], threshold: float) -> float:
    return (
        sum(score >= threshold for score in scores) / len(scores)
        if scores
        else float("nan")
    )


def threshold_for_max_fmr(
    negative_scores: Sequence[float],
    target: float,
) -> float:
    if not 0.0 <= target <= 1.0:
        raise SystemExit("FMR targets must be between 0 and 1")
    ordered = sorted(negative_scores, reverse=True)
    allowed_false_matches = math.floor(target * len(ordered))
    if allowed_false_matches >= len(ordered):
        return -1.0
    boundary = ordered[allowed_false_matches]
    threshold = float(
        np.nextafter(np.float32(boundary), np.float32(math.inf))
    )
    if threshold > 1.0:
        raise SystemExit(
            "Requested FMR cannot be achieved by the calibration score distribution"
        )
    return threshold


def percentile_interval(
    values: Sequence[float],
    confidence: float = 0.95,
) -> list[float]:
    if not values:
        return [float("nan"), float("nan")]
    alpha = (1.0 - confidence) / 2.0
    return [
        float(np.quantile(values, alpha)),
        float(np.quantile(values, 1.0 - alpha)),
    ]


def bootstrap_rate_interval(
    scores: Sequence[float],
    threshold: float,
    samples: int,
    seed: int,
) -> list[float]:
    if not scores or samples <= 0:
        return [float("nan"), float("nan")]
    rng = random.Random(seed)
    estimates: list[float] = []
    count = len(scores)
    for _ in range(samples):
        accepted = 0
        for _ in range(count):
            if scores[rng.randrange(count)] >= threshold:
                accepted += 1
        estimates.append(accepted / count)
    return percentile_interval(estimates)


def metric_block(
    positives: Sequence[float],
    negatives: Sequence[float],
    threshold: float,
    bootstrap_samples: int,
    seed: int,
) -> dict[str, object]:
    false_match_rate = rate(negatives, threshold)
    true_match_rate = rate(positives, threshold)
    return {
        "threshold": threshold,
        "falseMatchRate": false_match_rate,
        "trueMatchRate": true_match_rate,
        "falseNonMatchRate": 1.0 - true_match_rate,
        "falseMatchRate95Ci": bootstrap_rate_interval(
            negatives,
            threshold,
            bootstrap_samples,
            seed,
        ),
        "trueMatchRate95Ci": bootstrap_rate_interval(
            positives,
            threshold,
            bootstrap_samples,
            seed + 1,
        ),
    }


def subgroup_metrics(
    scored: Sequence[ScoredPair],
    review_threshold: float,
    high_threshold: float,
) -> dict[str, object]:
    result: dict[str, object] = {}
    for field in ("demographic_group", "device_class"):
        groups: dict[str, list[ScoredPair]] = defaultdict(list)
        for item in scored:
            groups[getattr(item.pair, field)].append(item)
        field_result: dict[str, object] = {}
        for name, items in sorted(groups.items()):
            positives = [item.score for item in items if item.pair.same_person]
            negatives = [item.score for item in items if not item.pair.same_person]
            if not positives or not negatives:
                field_result[name] = {
                    "positivePairs": len(positives),
                    "negativePairs": len(negatives),
                    "note": (
                        "Both positive and negative pairs are required for "
                        "subgroup rates."
                    ),
                }
                continue
            field_result[name] = {
                "positivePairs": len(positives),
                "negativePairs": len(negatives),
                "reviewFalseMatchRate": rate(negatives, review_threshold),
                "reviewTrueMatchRate": rate(positives, review_threshold),
                "highFalseMatchRate": rate(negatives, high_threshold),
                "highTrueMatchRate": rate(positives, high_threshold),
            }
        result[field] = field_result
    return result


def require_counts(
    scored: Sequence[ScoredPair],
    split: str,
    minimum_positive: int,
    minimum_negative: int,
) -> tuple[list[float], list[float]]:
    positives = [
        item.score
        for item in scored
        if item.pair.split == split and item.pair.same_person
    ]
    negatives = [
        item.score
        for item in scored
        if item.pair.split == split and not item.pair.same_person
    ]
    if len(positives) < minimum_positive or len(negatives) < minimum_negative:
        raise SystemExit(
            f"{split} split has {len(positives)} positive and "
            f"{len(negatives)} negative accepted pairs; minimums are "
            f"{minimum_positive} and {minimum_negative}."
        )
    return positives, negatives


def main() -> int:
    args = parse_args()
    if not 0.0 <= args.high_max_fmr <= args.review_max_fmr <= 1.0:
        raise SystemExit("Require 0 <= high-max-fmr <= review-max-fmr <= 1")
    if not 0.0 <= args.max_rejection_rate < 1.0:
        raise SystemExit("max-rejection-rate must be in [0, 1)")

    yunet_sha = verify_model(
        args.yunet,
        PINNED_YUNET_SHA256,
        YUNET_SIZE_BYTES,
        args.allow_unpinned_models,
    )
    sface_sha = verify_model(
        args.sface,
        PINNED_SFACE_SHA256,
        SFACE_SIZE_BYTES,
        args.allow_unpinned_models,
    )
    pairs = load_manifest(args.manifest, args.root)
    assert_identity_disjoint(pairs)

    pipeline = Pipeline(args.yunet, args.sface)
    scored, rejections = score_pairs(pipeline, pairs)
    rejection_rate = 1.0 - len(scored) / len(pairs)
    if rejection_rate > args.max_rejection_rate:
        raise SystemExit(
            f"Quality rejection rate {rejection_rate:.2%} exceeds limit "
            f"{args.max_rejection_rate:.2%}. Improve the corpus or inspect "
            "quality gates before calibrating."
        )

    calibration_positive, calibration_negative = require_counts(
        scored,
        "calibration",
        args.minimum_calibration_positive,
        args.minimum_calibration_negative,
    )
    test_positive, test_negative = require_counts(
        scored,
        "test",
        args.minimum_test_positive,
        args.minimum_test_negative,
    )

    review_threshold = threshold_for_max_fmr(
        calibration_negative,
        args.review_max_fmr,
    )
    high_threshold = threshold_for_max_fmr(
        calibration_negative,
        args.high_max_fmr,
    )
    if high_threshold < review_threshold:
        raise SystemExit("High threshold became looser than review threshold")

    calibration_review = metric_block(
        calibration_positive,
        calibration_negative,
        review_threshold,
        args.bootstrap_samples,
        args.seed,
    )
    calibration_high = metric_block(
        calibration_positive,
        calibration_negative,
        high_threshold,
        args.bootstrap_samples,
        args.seed + 10,
    )
    test_review = metric_block(
        test_positive,
        test_negative,
        review_threshold,
        args.bootstrap_samples,
        args.seed + 20,
    )
    test_high = metric_block(
        test_positive,
        test_negative,
        high_threshold,
        args.bootstrap_samples,
        args.seed + 30,
    )

    test_scored = [item for item in scored if item.pair.split == "test"]
    payload = {
        "schemaVersion": 1,
        "reviewThreshold": review_threshold,
        "highSimilarityThreshold": high_threshold,
        "sfaceSha256": sface_sha,
        "yunetSha256": yunet_sha,
        "pipelineVersion": PIPELINE_VERSION,
        "positivePairCount": len(test_positive),
        "negativePairCount": len(test_negative),
        "reviewFalseMatchRate": test_review["falseMatchRate"],
        "highFalseMatchRate": test_high["falseMatchRate"],
        "reviewTrueMatchRate": test_review["trueMatchRate"],
        "highTrueMatchRate": test_high["trueMatchRate"],
        "source": "Identity-disjoint held-out Dossier YuNet/SFace evaluation",
        "calibrationSplit": {
            "positivePairs": len(calibration_positive),
            "negativePairs": len(calibration_negative),
            "review": calibration_review,
            "high": calibration_high,
        },
        "heldOutTest": {
            "positivePairs": len(test_positive),
            "negativePairs": len(test_negative),
            "review": test_review,
            "high": test_high,
            "subgroups": subgroup_metrics(
                test_scored,
                review_threshold,
                high_threshold,
            ),
        },
        "quality": {
            "inputPairs": len(pairs),
            "acceptedPairs": len(scored),
            "rejectedPairs": len(pairs) - len(scored),
            "rejectionRate": rejection_rate,
            "rejectionReasons": rejections,
        },
        "targets": {
            "reviewMaxFalseMatchRate": args.review_max_fmr,
            "highMaxFalseMatchRate": args.high_max_fmr,
        },
        "notes": [
            "Thresholds were selected only on the calibration split.",
            (
                "Reported operating characteristics are from "
                "identity-disjoint held-out test identities."
            ),
            (
                "Face correlation remains supporting evidence and does not "
                "prove account ownership."
            ),
        ],
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, args.output)

    print(json.dumps(payload["heldOutTest"], indent=2, sort_keys=True))
    print(f"Wrote hash-bound calibration: {args.output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
