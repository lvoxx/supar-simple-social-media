package com.lvoxx.sssm.media.repository;

import com.lvoxx.sssm.media.domain.Media;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaRepository extends JpaRepository<Media, UUID> {
}
