import os
from pathlib import Path

import numpy as np
import pytest
import torch

os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")

from mouna_encoder.model import EMBED_DIM, LipEncoder
from mouna_encoder.preprocess import to_encoder_input

WEIGHTS = Path(__file__).resolve().parents[1] / "weights"


def test_shapes_with_random_weights():
    with torch.no_grad():
        y = LipEncoder().eval()(torch.rand(2, 1, 12, 88, 88))
    assert y.shape == (2, EMBED_DIM)


def test_preprocess_resamples_to_25fps_and_crops_88():
    crops = np.random.default_rng(0).integers(0, 255, size=(60, 96, 96), dtype=np.uint8)
    t = np.arange(60) * 1000 / 30  # 2 s at 30 fps
    x = to_encoder_input(crops, t)
    assert x.shape == (1, 1, 50, 88, 88)
    assert 0 <= x.min() and x.max() <= 1


@pytest.mark.skipif(not (WEIGHTS / "weight.bin").exists(), reason="run python -m mouna_encoder fetch first")
def test_parity_with_coreml_graph():
    from mouna_encoder.coreml import load_program, run
    from mouna_encoder.load import from_coreml

    x = np.random.default_rng(1).random((1, 1, 7, 88, 88)).astype(np.float32)
    block, consts = load_program(WEIGHTS)
    ref = np.asarray(next(iter(run(block, consts, {"v": x}).values())))
    with torch.no_grad():
        y = from_coreml(WEIGHTS)(torch.from_numpy(x)).numpy()
    assert np.abs(y - ref).max() < 1e-3


def test_npu_split_is_exactly_the_full_model():
    from mouna_encoder.model import Frontend2D, Temporal, stack_frames

    torch.manual_seed(0)
    m = LipEncoder().eval()
    v = torch.rand(1, 1, 9, 88, 88)
    with torch.no_grad():
        assert torch.allclose(m(v), Temporal(m)(Frontend2D(m)(stack_frames(v))[None]), atol=1e-5)


def test_unrolled_static_encoder_matches_the_gru_model():
    from mouna_encoder.model import StaticEncoder, stack_frames

    torch.manual_seed(1)
    m = LipEncoder().eval()
    v = torch.rand(1, 1, 6, 88, 88)
    with torch.no_grad():
        assert torch.allclose(m(v), StaticEncoder(m)(stack_frames(v)), atol=1e-5)
